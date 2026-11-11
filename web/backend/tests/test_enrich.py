from app import enrich

def test_vendor_by_oui_and_local_bit():
    assert "Apple" in enrich.vendor_of("D8:00:4D:00:00:01")
    assert enrich.vendor_of("DA:00:4D:00:00:01") == enrich.vendor_of("D8:00:4D:00:00:01")
    assert enrich.vendor_of("FE:FF:FF:00:00:01") == ""
    assert enrich.is_local_admin("DA:00:00:00:00:00") and not enrich.is_local_admin("D8:00:00:00:00:00")

def test_city_nearest_within_radius_else_empty():
    assert enrich.city_of(-21.14, -48.97) == "Catanduva - SP"
    assert enrich.city_of(-23.55, -46.63) == "São Paulo - SP"
    assert enrich.city_of(-14.0, -52.0) == ""
    assert enrich.city_of(None, None) == ""

def test_default_name():
    assert enrich.is_default_name("VIVO-1234") and not enrich.is_default_name("Casa da Maria") and not enrich.is_default_name("")
