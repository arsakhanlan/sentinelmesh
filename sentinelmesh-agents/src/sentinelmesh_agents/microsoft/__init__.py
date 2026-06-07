"""Microsoft AI ecosystem integrations for SentinelMesh.

This subpackage carries the adapters that let SentinelMesh govern agents
written against Microsoft's AI surfaces — Microsoft Agent Framework (MAF),
Foundry Agent Service, the Azure AI Evaluation SDK, and Foundry tracing.

Every module here is **optional**: importing this package never pulls in a
Microsoft dependency. Pull a specific module instead, and the missing
package is reported with a clear, actionable install hint:

    from sentinelmesh_agents.microsoft.maf_middleware import SentinelMiddleware

If ``agent-framework`` isn't installed, ``SentinelMiddleware`` itself still
works — its protocol is duck-typed against MAF's ``FunctionInvocationContext``
so unit tests can use a tiny fake context without pulling in MAF.
"""
