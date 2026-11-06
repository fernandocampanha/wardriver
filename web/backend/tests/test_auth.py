from app import auth


def test_correct_password_verifies():
    stored = auth.hash_password("abacate123")
    assert auth.verify_password("abacate123", stored) is True


def test_wrong_password_rejected():
    stored = auth.hash_password("abacate123")
    assert auth.verify_password("errada", stored) is False
    assert auth.verify_password("", stored) is False


def test_hash_is_salted_and_never_the_plain_password():
    a, b = auth.hash_password("mesma-senha"), auth.hash_password("mesma-senha")
    assert a != b
    assert "mesma-senha" not in a and "mesma-senha" not in b


def test_malformed_stored_hash_fails_closed():
    for bad in ("", "lixo", "scrypt$16384$8$1$so-tres-campos", "md5$abc$def"):
        assert auth.verify_password("qualquer", bad) is False
