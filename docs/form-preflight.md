# Offline Form Submission Preflight

`FormElement.preflight(...)` computes what a form submission would send, and validates it, without making any
network request. It is an offline companion to `FormElement.submit()` (which builds a `Connection`) and
`FormElement.formData()` (whose behavior is unchanged).

## API

- `form.preflight()` — preflight with no submitter.
- `form.preflight(submitter)` — preflight as if the given submit button had been clicked. If the submitter is an
  `<input type=image>`, the click coordinates default to `0,0` (matching the HTML standard's default when no
  coordinate was selected).
- `form.preflight(submitter, imageX, imageY)` — as above, with explicit image-button click coordinates. Negative
  coordinates are rejected with an `IllegalArgumentException`.

The returned `FormElement.PreflightResult` provides:

- `data()` — the field pairs (`Connection.KeyVal`) that would be submitted, in document order. Duplicate field
  names (e.g. repeated checkboxes) are retained.
- `errors()` — the `FormElement.PreflightError` list, in document order of their controls. Each error carries the
  failing `element()`, the failed `constraint()` name, a `message()`, and the element's source `position()`
  (line/column, when the parser ran with `Parser.setTrackPosition(true)`).
- `isValid()` — `true` when no constraint errors were found; a `false` result means the submission is blocked.

## Successful controls

Field construction follows the HTML standard's successful-controls rules:

- disabled controls are skipped, including descendants of a disabled `<fieldset>` — except controls within that
  fieldset's first `<legend>` child, which remain enabled;
- only the supplied submitter contributes a submit-button entry; other submit buttons, and `button`/`reset`
  inputs, are never successful;
- an image-button submitter contributes `name.x` / `name.y` entries (or `x` / `y` when unnamed);
- checkboxes and radios are only successful when checked (defaulting to the value `on`);
- a `<select multiple>` contributes one entry per selected option;
- controls associated with the form via the `form` attribute (including controls outside the form element) are
  included, in document order;
- unknown input types fall back to `text` semantics, per the HTML standard's missing-value default.

## Supported constraint subset

The preflight validates this subset of HTML constraints:

- `required` — on inputs (including checkboxes/radios), textareas, and selects;
- `pattern` — on text-like inputs; the value must fully match the pattern. An invalid pattern is ignored, per
  the HTML standard;
- `min` / `max` — on `number` and `range` inputs (numeric comparison);
- `step` — on `number` and `range` inputs, including decimal steps (e.g. `step=0.5`); `step=any` disables the
  check. A non-numeric value in a numeric input is reported with the constraint name `number`;
- `minlength` / `maxlength` — on text-like inputs and textareas.

Constraints not listed here (e.g. `type=email` syntax, date formats, `accept`) are not validated.

## Example

```java
Document doc = Jsoup.parse(new File("src/test/resources/htmltests/form-preflight.html"), "UTF-8",
    "https://example.com/", Parser.htmlParser().setTrackPosition(true));
FormElement form = (FormElement) doc.selectFirst("form#order");
Element submitter = form.selectFirst("input[value=save]");

FormElement.PreflightResult result = form.preflight(submitter);
if (!result.isValid()) {
    for (FormElement.PreflightError error : result.errors())
        System.out.println(error); // constraint, source position, and message
}
```

A local fixture exercising two submit buttons, repeated checkboxes, a disabled fieldset with a legend exception,
a decimal `step`, a multiple select, an external `form`-attribute control, and an image submitter lives at
`src/test/resources/htmltests/form-preflight.html`.

## Printing fields and failing controls

The following command runs a report test that parses the fixture, preflights it, and prints the submitted fields
and the failing controls (typically well under 90 seconds):

```sh
mvn -q -Dtest=FormElementPreflightTest#printPreflightReport test
```

Sample output:

```
Preflight of /order with submitter action=save
Fields:
  customer=jsoup
  zip=abc
  quantity=2.3
  note=
  topping=cheese
  topping=bacon
  sides=fries
  sides=salad
  legend-note=still enabled
  action=save
  external=linked
Errors:
  pattern at line 7, column 5 <input name=zip>: Value does not match the pattern [0-9]{5}
  step at line 8, column 5 <input name=quantity>: Value 2.3 is not aligned to step 0.5 from 0.0
  required at line 9, column 5 <input name=note>: Required control has no value
```
