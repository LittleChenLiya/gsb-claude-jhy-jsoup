# Sanitization Report - Local Test Commands

## Run the report tests

```bash
mvn test -Dtest=CleanerTest#report*
```

## Run all Cleaner tests

```bash
mvn test -Dtest=CleanerTest
```

## Run the before/after demo

```bash
mvn compile exec:java -Dexec.mainClass=org.jsoup.examples.SanitizationReportDemo
```

Or directly with Java:

```bash
java -cp target/classes org.jsoup.examples.SanitizationReportDemo
```

## Run with local Maven repository cache (D drive)

```bash
mvn test -Dtest=CleanerTest -Dmaven.repo.local=D:/path/to/m2repo
```
