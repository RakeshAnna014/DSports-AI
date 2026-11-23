"""The topic policy: an exhaustive role x topic matrix, so a change to who may see what cannot happen by accident."""
from dataclasses import dataclass

import pytest

from app.guard.topic import ALLOWED, Audience, Topic, audience_of, is_allowed


@dataclass
class _P:
    is_admin: bool


EXPECTED = {
    (Audience.GUEST, Topic.SHOP): True, (Audience.GUEST, Topic.BOOKING): True, (Audience.GUEST, Topic.CRICKET): True,
    (Audience.GUEST, Topic.BUSINESS): False, (Audience.GUEST, Topic.OFF_TOPIC): False,
    (Audience.CUSTOMER, Topic.SHOP): True, (Audience.CUSTOMER, Topic.BOOKING): True, (Audience.CUSTOMER, Topic.CRICKET): True,
    (Audience.CUSTOMER, Topic.BUSINESS): False, (Audience.CUSTOMER, Topic.OFF_TOPIC): False,
    (Audience.ADMIN, Topic.SHOP): True, (Audience.ADMIN, Topic.BOOKING): True, (Audience.ADMIN, Topic.CRICKET): True,
    (Audience.ADMIN, Topic.BUSINESS): True, (Audience.ADMIN, Topic.OFF_TOPIC): False,
}


def test_the_matrix_covers_every_role_and_topic():
    assert set(EXPECTED) == {(a, t) for a in Audience for t in Topic}


@pytest.mark.parametrize("audience,topic", sorted(EXPECTED, key=str))
def test_policy_matrix(audience, topic):
    assert is_allowed(audience, topic) is EXPECTED[(audience, topic)]


def test_off_topic_is_refused_for_everyone_and_business_only_for_admin():
    assert all(Topic.OFF_TOPIC not in topics for topics in ALLOWED.values())
    assert [a for a in Audience if Topic.BUSINESS in ALLOWED[a]] == [Audience.ADMIN]


def test_audience_comes_from_the_verified_principal_only():
    assert audience_of(None) is Audience.GUEST
    assert audience_of(_P(is_admin=False)) is Audience.CUSTOMER
    assert audience_of(_P(is_admin=True)) is Audience.ADMIN
