# Bentrix SDK for Java

The Java client of [Bentrix](https://bentrix.net) — charts, a replay laboratory and trading through your own MetaTrader — for a program or an agent.

It finds the Bentrix running on this computer through the agents folder Bentrix writes at every start, pairs with it under the name you give — the name you see in ☰ › Settings › **Agents**, where the pairing's permissions are set — and calls every tool of Bentrix's capability catalogue over MCP. Nothing is typed: no address, no key.

The client is generated from the catalogue of the Bentrix release it came with, and checks when it connects that the running Bentrix serves the same major version.

## What you need

A JDK (17 or later), and Bentrix open on the same computer.

## Install

The client is source. Compile it with your program, from a clone of this repository:

```bash
javac -d out src/bentrix/Json.java src/bentrix/BentrixException.java \
      mcp/src/bentrix/mcp/*.java MyAgent.java
java -cp out MyAgent
```

## Connect

```java
try (Bentrix b = Bentrix.connect("my-agent")) {
    Object state = b.workspace.getState(new Bentrix.WorkspaceApi.GetStateArgs());
    // ... read the workbench's uri from the state, then:
    Object book = b.trade.book(new Bentrix.TradeApi.BookArgs(home, owner, bench));
}
```

## The engine in your own process

`src/bentrix/Native.java` and its Gym (the in-process engine) use Bentrix's engine library, which is not part of this repository.

## Documentation

Every method, its arguments, the permission it needs and what it answers: **https://bentrix.net/en/docs/sdk/java/**

## Licence

MIT — see [LICENSE](LICENSE). The licence covers this source code; Bentrix itself is not covered by it.
