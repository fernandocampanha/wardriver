import argparse
import getpass

from . import auth

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--password", nargs="?", const=True, default=False,
                        help="gera a senha do painel em vez do token de upload; sem valor, pede com prompt oculto")
    args = parser.parse_args()
    if args.password:
        password = args.password if isinstance(args.password, str) else getpass.getpass("Senha do painel: ")
        if not password:
            print("senha vazia: operacao cancelada")
            return 1
        print("Variavel de ambiente do servidor:")
        print(f"  WARDRIVER_WEB_PANEL_PASSWORD_HASH={auth.hash_password(password)}")
        return 0
    token = auth.new_token()
    print(f"Token de upload (guarde em local seguro; nao sera mostrado de novo):\n  {token}\n")
    print("Variavel de ambiente do servidor:")
    print(f"  WARDRIVER_WEB_UPLOAD_TOKEN_SHA256={auth.token_sha256(token)}")
    return 0
if __name__ == "__main__":
    raise SystemExit(main())
