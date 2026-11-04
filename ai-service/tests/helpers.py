from __future__ import annotations

from dataclasses import dataclass

from app.agent.service import ChatService
from app.auth.jwt import Principal, verify_token
from app.services import Services, build_services
from app.tools.registry import ToolContext
from tests.conftest import make_settings, token_for
from tests.fakes import DEFAULT_CHUNKS, DEFAULT_PRODUCTS, USER_A, USER_B, FakeDSports, FakeLLM, FakeRetriever


@dataclass
class Rig:
    services: Services
    chat: ChatService
    dsports: FakeDSports
    llm: FakeLLM
    retriever: FakeRetriever
    alice: Principal
    bob: Principal

    def ctx(self, principal: Principal | None, max_calls: int = 8) -> ToolContext:
        return ToolContext(principal=principal, dsports=self.services.dsports, settings=self.services.settings, services=self.services,
                           max_calls=max_calls)


async def make_rig(*, llm: FakeLLM | None = None, dsports: FakeDSports | None = None, **settings_overrides) -> Rig:
    settings = make_settings(**settings_overrides)
    dsports = dsports or FakeDSports()
    llm = llm or FakeLLM()
    retriever = FakeRetriever(dsports.products, [dict(c) for c in DEFAULT_CHUNKS])
    services = await build_services(settings, http=dsports.client(), llm_inner=llm, retriever=retriever)
    services.llm._delay = 0  # no waiting between retries in unit tests
    tokens = {}
    for name, user in (("alice", USER_A), ("bob", USER_B)):
        token = token_for(user)
        dsports.customer(user, token)
        tokens[name] = verify_token(token, settings)
    return Rig(services, ChatService(services), dsports, llm, retriever, tokens["alice"], tokens["bob"])
