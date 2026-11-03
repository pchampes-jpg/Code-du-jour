#!/usr/bin/env bash
# Construit et contrôle l'AAR pf-android sur un poste équipé du SDK Android. Voir docs/CONSTRUIRE_AAR.md.
# Usage : tools/construire-aar.sh            (construit + publie dans dist-maven/ + contrôle)
#         tools/construire-aar.sh --verifier <fichier.aar>   (contrôle seulement)
# N'installe rien, ne modifie aucun fichier du dépôt (sorties : build/ et dist-maven/, ignorés par Git).
set -euo pipefail
cd "$(dirname "$0")/.."

verifier_aar() {
  local aar="$1" tmp; tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' RETURN
  [ -f "$aar" ] || { echo "ECHEC : AAR introuvable : $aar" >&2; return 1; }
  unzip -q -o "$aar" -d "$tmp"
  for f in AndroidManifest.xml classes.jar; do [ -f "$tmp/$f" ] || { echo "ECHEC : $f absent de l'AAR" >&2; return 1; }; done
  local n; n=$(unzip -l "$tmp/classes.jar" | grep -c 'pf/android/.*\.class' || true)
  [ "$n" -ge 7 ] || { echo "ECHEC : classes pf/android attendues (>=7), trouvées : $n" >&2; return 1; }
  for c in AndroidSqlDb AndroidKeystoreVault PlatformRuntime PfSyncJobService SyncScheduler NetworkSyncTrigger; do
    unzip -l "$tmp/classes.jar" | grep -q "pf/android/$c.class" || { echo "ECHEC : classe $c absente" >&2; return 1; }
  done
  grep -q 'PfSyncJobService' "$tmp/AndroidManifest.xml" || { echo "ECHEC : service de tâche absent du manifeste" >&2; return 1; }
  if grep -aq 'pf/client/PlatformClient' "$tmp/classes.jar"; then echo "NOTE : pf-core est embarqué dans l'AAR (inattendu)"; fi
  echo "AAR contrôlé : $n classes pf.android, manifeste et service présents"
  echo "SHA-256 : $(sha256sum "$aar" | cut -d' ' -f1)  $(basename "$aar")  ($(stat -c %s "$aar") octets)"
}

if [ "${1:-}" = "--verifier" ]; then verifier_aar "${2:?chemin du fichier AAR requis}"; exit $?; fi

manque=0
echo "== Prérequis =="
jv=$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+)[.".].*/\1/' || true)
if [ -z "$jv" ] || [ "$jv" -lt 17 ] 2>/dev/null; then echo "MANQUE : JDK 17 ou plus (trouvé : ${jv:-aucun})"; manque=1; else echo "OK     : JDK $jv"; fi
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$sdk" ] && [ -f local.properties ]; then sdk=$(sed -n 's/^sdk.dir=//p' local.properties | head -1); fi
if [ -z "$sdk" ] || [ ! -d "$sdk" ]; then echo "MANQUE : SDK Android (ANDROID_HOME / ANDROID_SDK_ROOT / sdk.dir dans local.properties) : '${sdk:-non défini}'"; manque=1
else
  [ -d "$sdk/platforms/android-34" ] && echo "OK     : platforms;android-34" || { echo "MANQUE : platforms;android-34 dans $sdk"; manque=1; }
  ls -d "$sdk"/build-tools/34.* >/dev/null 2>&1 && echo "OK     : build-tools;34.x" || { echo "MANQUE : build-tools;34.0.0 dans $sdk"; manque=1; }
  [ -f "$sdk/licenses/android-sdk-license" ] && echo "OK     : licences SDK acceptées" || { echo "MANQUE : licences SDK non acceptées (sdkmanager --licenses)"; manque=1; }
fi
for h in dl.google.com repo.maven.apache.org plugins.gradle.org; do
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 -I "https://$h/" || true)
  if [ "$code" = "000" ] || [ "$code" = "403" ] || [ "$code" = "407" ]; then
    echo "MANQUE : accès réseau à $h - code $code"; manque=1
  else
    echo "OK     : accès à $h - code $code"
  fi
done
[ "$manque" -eq 0 ] || { echo "== ARRET : prérequis manquants (aucune construction tentée) =="; exit 2; }

echo "== Construction =="
export ANDROID_HOME="$sdk"
./gradlew --no-daemon :pf-android:assembleRelease :pf-core:publish :pf-android:publish
aar=$(ls pf-android/build/outputs/aar/*-release.aar | head -1)
echo "== Contrôle =="
verifier_aar "$aar"
echo "Publication Maven locale : dist-maven/ (pf-core jar + pf-android aar + POM)"
