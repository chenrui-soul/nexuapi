"""Bounded SSH diagnostics. Never print environment values or credentials."""
import argparse
import base64
import pathlib
import sys
import paramiko

sys.stdout.reconfigure(encoding="utf-8")
parser = argparse.ArgumentParser()
parser.add_argument("file")
parser.add_argument("--timeout", type=int, default=45)
args = parser.parse_args()
client = paramiko.SSHClient()
client.load_system_host_keys()
client.connect("8.218.238.141", username="root",
               key_filename=str(pathlib.Path.home()/".ssh/nexus-deploy-ed25519"),
               timeout=8, banner_timeout=15, auth_timeout=10)
try:
    source = pathlib.Path(args.file).read_bytes().replace(b"\r\n", b"\n")
    command = "echo '" + base64.b64encode(source).decode() + "' | base64 -d | bash"
    stdin, stdout, stderr = client.exec_command(command, timeout=args.timeout)
    print(stdout.read().decode("utf-8", errors="replace"))
    print(stderr.read().decode("utf-8", errors="replace"), file=sys.stderr)
    sys.exit(stdout.channel.recv_exit_status())
finally:
    client.close()
