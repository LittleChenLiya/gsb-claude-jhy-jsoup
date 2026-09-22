# Security Policy

## Supported Versions

Security fixes are not back-ported. Please make sure you are running at least the latest [release version](https://jsoup.org/download) of jsoup.

Please remember that jsoup is an Open Source library and is provided without any warranty. Before using jsoup in a critical environment, you should satisfy yourself that it works correctly and securely for your needs.

## Reporting a Vulnerability

If you believe or suspect you have identified a security vulnerability, please [report it](https://github.com/jhy/jsoup/security/advisories)
via the "Report a Vulnerability" button in Security Advisories. 
([Details](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing/privately-reporting-a-security-vulnerability))

We follow [Coordinated Disclosure](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing/about-coordinated-disclosure-of-security-vulnerabilities) practices and ask that you do too.

Please provide as much detail as possible in your report, including the steps to reproduce the vulnerability and sample code.

Alternatively to using GitHub, or if you have a security question, please email `security@jsoup.org`.

Before reporting, please confirm that the issue reproduces through a supported and correctly implemented use of jsoup.

Reports must identify a security guarantee violated by jsoup itself. Application errors, mismatched protocols or character encodings, security risks introduced by a custom `Safelist` policy, and issues that depend on a separate vulnerability or broken trust boundary in another system are outside jsoup’s security boundary and will be closed.

Potential correctness bugs that do not cross this boundary should be reported through the regular issue tracker.

## Cleaner Audit Reports

`Cleaner.cleanWithReport(Document, maxActions, maxValueLength)` returns the cleaned document together with an audit report of every removal and enforced-attribute override the `Cleaner` decided on, each with the original node path, the source position when tracked, and the safelist reason. When using it, keep in mind:

- The report is observational only. The cleaned document from `cleanWithReport` is identical to `clean(Document)` for the same input and safelist, and the same safelist guarantees apply to it. The report limit (`maxActions`) bounds report memory only; cleaning always runs to completion.
- Reported paths and source positions refer to the original, untrusted input document. Treat them, and any captured values, as untrusted data: do not interpolate them into HTML, SQL, or other contexts without appropriate escaping.
- Removed source values are not captured by default. If you opt in with a `maxValueLength`, the report will contain length-limited fragments of the untrusted input; store and display it with the same care as the raw input.

## Fixing Vulnerabilities

We take all vulnerability reports seriously and strive to fix them as quickly as possible. Once we receive a report, we will verify the vulnerability and its impact. We will then work to develop and test a fix for the vulnerability, and release it as soon as possible.
