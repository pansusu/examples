# ADK Customer Service (Java)

Java translation of `adksamples/customer-service`, implemented using Spring AI Alibaba Agent Framework.

## Run

1. Set model credentials for DashScope or your configured provider.
2. Build and run:

```bash
mvn -q -DskipTests package
java -jar target/adk-customer-service-0.0.1-SNAPSHOT.jar
```

The Spring context provides a `ReactAgent` bean named `project_pro` with tools matching the Python sample.
