"""Banc d'essai : lance le serveur Plateforme Commune v0.1.2 NON MODIFIE en HTTPS local, avec données fictives.

Usage : python serveur_essai.py <dossier_plateforme> <dossier_travail> [--san-dns nom]
  - dossier_plateforme : arbre de la plateforme (contient server/src), lu seulement ;
  - dossier_travail    : dossier jetable (base, fichiers, certificats de TEST générés ici).
Protocole : écrit une ligne JSON de démarrage sur stdout ; lit des commandes JSON (une par ligne) sur stdin :
  {"cmd":"enroll","perms":"*"}          -> {"code": "..."}      (code d'enrôlement fictif à usage unique)
  {"cmd":"revoke","device_id":"..."}    -> {"ok": true}
  {"cmd":"file","app_id":"..","file_id":".."} -> {"sha256": "...", "size": n} (contenu publié côté serveur)
  {"cmd":"entity","app_id":"..","entity":"..","entity_id":".."} -> {"row": {...}|null}
  {"cmd":"count_ops","app_id":".."}     -> {"n": ..}
  {"cmd":"stop"}                        -> arrêt
Aucun secret réel : clés et certificats sont générés à la volée dans le dossier de travail et jetés ensuite.
"""
import datetime, hashlib, ipaddress, json, os, sys, threading

plat, work = sys.argv[1], sys.argv[2]
san_dns = None
if "--san-dns" in sys.argv:
    san_dns = sys.argv[sys.argv.index("--san-dns") + 1]
sys.path.insert(0, os.path.join(plat, "server", "src"))

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID

from pf_server.config import Config
from pf_server.http_api import PlatformServer
from pf_server.platform import Platform


def make_ca_and_cert(dirpath, dns_names, ips):
    now = datetime.datetime.now(datetime.timezone.utc)
    ca_key = ec.generate_private_key(ec.SECP256R1())
    ca_name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "PF TEST CA (fictive)")])
    ca = (x509.CertificateBuilder().subject_name(ca_name).issuer_name(ca_name).public_key(ca_key.public_key())
          .serial_number(x509.random_serial_number()).not_valid_before(now - datetime.timedelta(days=1))
          .not_valid_after(now + datetime.timedelta(days=30))
          .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
          .add_extension(x509.KeyUsage(digital_signature=True, key_cert_sign=True, crl_sign=True, content_commitment=False,
                                       key_encipherment=False, data_encipherment=False, key_agreement=False,
                                       encipher_only=False, decipher_only=False), critical=True)
          .sign(ca_key, hashes.SHA256()))
    key = ec.generate_private_key(ec.SECP256R1())
    sans = [x509.DNSName(d) for d in dns_names] + [x509.IPAddress(ipaddress.ip_address(i)) for i in ips]
    cert = (x509.CertificateBuilder().subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "pf-essai")]))
            .issuer_name(ca_name).public_key(key.public_key()).serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(days=1)).not_valid_after(now + datetime.timedelta(days=30))
            .add_extension(x509.SubjectAlternativeName(sans), critical=False)
            .add_extension(x509.ExtendedKeyUsage([x509.oid.ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
            .sign(ca_key, hashes.SHA256()))
    paths = {"ca": os.path.join(dirpath, "ca.pem"), "cert": os.path.join(dirpath, "server.pem"), "key": os.path.join(dirpath, "server.key")}
    open(paths["ca"], "wb").write(ca.public_bytes(serialization.Encoding.PEM))
    open(paths["cert"], "wb").write(cert.public_bytes(serialization.Encoding.PEM))
    fd = os.open(paths["key"], os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as fh:
        fh.write(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    return paths


os.makedirs(work, exist_ok=True)
tls = os.path.join(work, "tls"); os.makedirs(tls)
paths = make_ca_and_cert(tls, [san_dns] if san_dns else ["localhost"], [] if san_dns else ["127.0.0.1"])
cfg = Config(data_root=os.path.join(work, "data"), host="127.0.0.1", port=0, tls_cert=paths["cert"], tls_key=paths["key"])
platform = Platform(cfg)
platform.register_app({"app_id": "essai", "schema_version": 1, "entities": {
    "commande": {"numbering": ["CMD"]}, "note": {}, "photo": {}}})
platform.register_app({"app_id": "autre", "schema_version": 1, "entities": {"note": {}}})
server = PlatformServer(platform).start()
print(json.dumps({"url": server.base_url, "ca": paths["ca"], "data": cfg.data_root}), flush=True)

for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    req = json.loads(line)
    c = req["cmd"]
    if c == "enroll":
        out = {"code": platform.auth.create_enrollment("utilisateur-fictif", req.get("apps", ["essai"]), req.get("perms", "*").split(","), req.get("ttl", 3600))}
    elif c == "revoke":
        out = {"ok": platform.auth.revoke_device(req["device_id"])}
    elif c == "file":
        try:
            data = platform.files.read_file(req["app_id"], req["file_id"])
            out = {"sha256": hashlib.sha256(data).hexdigest(), "size": len(data)}
        except FileNotFoundError:
            out = {"sha256": None, "size": None}
    elif c == "entity":
        conn = platform.conn()
        try:
            r = conn.execute("SELECT * FROM entities WHERE app_id=? AND entity=? AND entity_id=?", (req["app_id"], req["entity"], req["entity_id"])).fetchone()
            out = {"row": dict(r) if r else None}
        finally:
            conn.close()
    elif c == "count_ops":
        conn = platform.conn()
        try:
            out = {"n": conn.execute("SELECT COUNT(*) FROM ops WHERE app_id=?", (req["app_id"],)).fetchone()[0]}
        finally:
            conn.close()
    elif c == "stop":
        print(json.dumps({"ok": True}), flush=True)
        break
    else:
        out = {"error": "commande inconnue"}
    print(json.dumps(out), flush=True)
server.stop()
