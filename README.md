# Bentrix SDK for Java

The Java client of [Bentrix](https://bentrix.net) — charts, a replay laboratory and trading through your own MetaTrader — for a program or an agent.

It finds the Bentrix running on this computer through the agents folder Bentrix writes at every start, pairs with it under the name you give — the name you see in ☰ › Settings › **Agents**, where the pairing's permissions are set — and calls every tool of Bentrix's capability catalogue over MCP. Nothing is typed: no address, no key.

The client is generated from the catalogue of the Bentrix release it came with, and checks when it connects that the running Bentrix serves the same major version.

## What you need

A JDK 21 or later (22 or later for the engine below), and Bentrix open on the same computer.

## Install

Everything is the one artifact `net.bentrix:bentrix` on Maven Central - the client of a running Bentrix and the engine in your own process. With Maven:

```xml
<dependency>
  <groupId>net.bentrix</groupId>
  <artifactId>bentrix</artifactId>
  <version>0.2.383</version>
</dependency>
```

With Gradle: `implementation("net.bentrix:bentrix:0.2.383")`.

Or from a clone of this repository, compiled with your program:

```bash
javac -d out src/bentrix/Json.java src/bentrix/BentrixException.java \
      mcp/src/bentrix/mcp/*.java MyAgent.java
java -cp out MyAgent
```

## Connect

```java
import bentrix.mcp.Bentrix;

try (Bentrix b = Bentrix.connect("my-agent")) {
    Object state = b.workspace.getState(new Bentrix.WorkspaceApi.GetStateArgs());
    // ... read the workbench's uri from the state, then:
    Object book = b.trade.book(new Bentrix.TradeApi.BookArgs(home, owner, bench));
}
```

A workbench as one object: its orders, its book, its clock.

```java
import bentrix.mcp.Bench;

Bench bench = Bench.open(b);
Bench.Answer answer = bench.buy(0.1, new Bench.BuyOptions().sl(2300.0));
System.out.println(answer.ok() ? answer.outcome : answer.reason);
bench.step(new Bench.StepOptions().interval("H1"));
```

## The engine in your own process

The same artifact's `bentrix.Local` and the Gym's `bentrix.TradingEnv` — from a clone, `src/bentrix/Local.java`, `Native.java` and `TradingEnv.java` — run Bentrix's engine inside your program, with no window, through Java's Foreign Function and Memory API: Java 22 or later, run with `--enable-native-access=ALL-UNNAMED`. The engine is Bentrix's own library, `libbentrix`, which Bentrix's installer puts in its folder (`%LOCALAPPDATA%\Programs\BENTRIX\libbentrix.dll` on Windows) and the binding finds there; `BENTRIX_FFI` (the file) or `BENTRIX_FFI_DIR` (its folder) names another. It runs for a computer signed in to Bentrix with Plus or Pro; with Free, connect to the running application as above.

## Documentation

Every method, its arguments, the permission it needs and what it answers: **https://bentrix.net/en/docs/sdk/java/**

## Licence

MIT — see [LICENSE](https://github.com/bentrixhq/bentrix-java/blob/main/LICENSE). It covers this source code only: Bentrix's engine library (`libbentrix`) and the Bentrix application are Bentrix's own, under their own terms.
