# ADK Financial Advisor (Java)

Java translation of `adksamples/financial-advisor` using the Agent Tool pattern.

- Coordinator: `financial_coordinator` (ReactAgent)
- Tools: sub-agents wrapped via `AgentTool.getFunctionToolCallback`

Run:
```bash
mvn -q -DskipTests package
java -jar target/adk-financial-advisor-0.0.1-SNAPSHOT.jar
```
