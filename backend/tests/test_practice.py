"""A5 practice checking: verdict + explanation for one question at a time, practice items only (attacks D1, D7, C2)."""
from tests.conftest import import_fixture, ip_headers, owner, signed_in


def ans(client, h, item, option, **extra):
    return client.post(f"/v1/practice/items/{item}/answer", json={"option": option, **extra}, headers=h)


def test_right_answer_gets_the_explanation(client):
    import_fixture()
    _, h = owner(client)
    r = ans(client, h, "ITM-0001", "b")
    assert r.status_code == 200, r.text
    j = r.json()
    assert j["correct"] is True and j["revealed"] is True and j["answer"] == "b"
    assert j["explanation"].startswith("FIXTURE-EXPLAIN-1") and j["why"] is None


def test_wrong_answer_explains_that_option_and_reveals_only_after_two_tries(client):
    import_fixture()
    _, h = owner(client)
    j = ans(client, h, "ITM-0001", "a").json()
    assert j == {"item_id": "ITM-0001", "correct": False, "attempts_wrong": 1, "why": "That would be ==.", "revealed": False}
    assert "answer" not in j and "explanation" not in j
    j = ans(client, h, "ITM-0001", "c").json()
    assert j["revealed"] is True and j["answer"] == "b" and j["explanation"].startswith("FIXTURE-EXPLAIN-1")


def test_reveal_counts_are_per_account(client):
    import_fixture()
    _, h1 = owner(client)
    _, h2 = owner(client)
    ans(client, h1, "ITM-0002", "a"); ans(client, h1, "ITM-0002", "b")
    assert ans(client, h2, "ITM-0002", "a").json()["revealed"] is False


def test_trace_items_are_checked_too(client):
    import_fixture()
    _, h = owner(client)
    assert ans(client, h, "ITM-0004", "b").json()["correct"] is True
    assert ans(client, h, "ITM-0004", "a").json()["why"] == "7 % 2 is 1, not 0."


def test_assessment_items_are_indistinguishable_from_unknown_ones(client):
    import_fixture()
    _, h = owner(client)
    a = ans(client, h, "ITM-0005", "a")       # purpose: assessment
    b = ans(client, h, "ITM-9999", "a")       # does not exist
    assert a.status_code == b.status_code == 404 and a.json() == b.json()
    assert "5" not in a.text and "FIXTURE" not in a.text


def test_needs_a_session_and_an_entitlement(client):
    import_fixture()
    assert client.post("/v1/practice/items/ITM-0001/answer", json={"option": "b"}).status_code == 401
    _, _, h = signed_in(client)  # signed in, but no book activated
    r = ans(client, h, "ITM-0001", "b")
    assert r.status_code == 403 and "answer" not in r.text


def test_another_editions_content_cannot_be_reached(client):
    import_fixture("ed1")
    import_fixture("ed2", "0.2.0")
    _, h = owner(client, "ed1")
    r = ans(client, h, "ITM-0001", "b", edition="ed2")
    assert r.status_code == 403 and "explanation" not in r.text


def test_tampered_requests_are_rejected(client):
    import_fixture()
    _, h = owner(client)
    assert ans(client, h, "ITM-0001", "z").status_code == 422                       # not one of the options
    assert ans(client, h, "ITM-0001", "b", correct=True).status_code == 422         # client cannot claim a verdict
    assert ans(client, h, "ITM-0001", "b", user_id="someone").status_code == 422
    assert client.post("/v1/practice/items/ITM-0001/answer", json={"option": "b' OR 1=1 --"}, headers=h).status_code == 422
    assert client.post("/v1/practice/items/../../x/answer", json={"option": "b"}, headers=h).status_code in (404, 422)
    assert client.post("/v1/practice/items/LSN-0001/answer", json={"option": "b"}, headers=h).status_code == 422


def test_unpublished_releases_are_not_checked(client):
    import_fixture(publish=False)
    _, h = owner(client)
    assert ans(client, h, "ITM-0001", "b").status_code == 404


def test_hammering_one_question_is_rate_limited(client):
    import_fixture()
    _, h = owner(client)
    codes = [ans(client, h, "ITM-0003", "b").status_code for _ in range(31)]
    assert codes[:30] == [200] * 30 and codes[30] == 429


def test_no_endpoint_hands_out_keys_before_an_attempt(client):
    """The manifest and every public file are free of answers and explanations (C3/D2), so a key is only ever seen per
    question, after an attempt, through the rate-limited endpoint."""
    import_fixture()
    _, h = owner(client)
    m = client.get("/v1/content/manifest", headers=h)
    body = m.text
    for f in m.json()["files"]:
        body += client.get("/v1/content/files/" + f["sha256"], headers=h).text
    assert "FIXTURE-EXPLAIN" not in body and '"answer"' not in body and "option_explanations" not in body
