package org.jsoup.nodes;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.helper.HttpConnection;
import org.jsoup.helper.Validate;
import org.jsoup.internal.SharedConstants;
import org.jsoup.internal.StringUtil;
import org.jsoup.parser.Tag;
import org.jsoup.select.Elements;
import org.jsoup.select.Evaluator;
import org.jsoup.select.Selector;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * An HTML Form Element provides ready access to the form fields/controls that are associated with it. It also allows a
 * form to easily be submitted.
 */
public class FormElement extends Element {
    private final Elements linkedEls = new Elements();
    // contains form submittable elements that were linked during the parse (and due to parse rules, may no longer be a child of this form)
    private static final Evaluator submittable = Selector.evaluatorOf(StringUtil.join(SharedConstants.FormSubmitTags, ", "));
    // as submittable, but also includes button elements (which may act as submitters)
    private static final Evaluator preflightable = Selector.evaluatorOf(StringUtil.join(SharedConstants.FormSubmitTags, ", ") + ", button");

    /**
     * Create a new, standalone form element.
     *
     * @param tag        tag of this element
     * @param baseUri    the base URI
     * @param attributes initial attributes
     */
    public FormElement(Tag tag, @Nullable String baseUri, @Nullable Attributes attributes) {
        super(tag, baseUri, attributes);
    }

    /**
     * Get the list of form control elements associated with this form.
     * @return form controls associated with this element.
     */
    public Elements elements() {
        // As elements may have been added or removed from the DOM after parse, prepare a new list that unions them:
        Elements els = select(submittable); // current form children
        for (Element linkedEl : linkedEls) {
            if (linkedEl.ownerDocument() != null && !els.contains(linkedEl)) {
                els.add(linkedEl); // adds previously linked elements, that weren't previously removed from the DOM
            }
        }

        return els;
    }

    /**
     * Add a form control element to this form.
     * @param element form control to add
     * @return this form element, for chaining
     */
    public FormElement addElement(Element element) {
        linkedEls.add(element);
        return this;
    }

    @Override
    protected void removeChild(Node out) {
        super.removeChild(out);
        linkedEls.remove(out);
    }

    /**
     Prepare to submit this form. A Connection object is created with the request set up from the form values. This
     Connection will inherit the settings and the cookies (etc) of the connection/session used to request this Document
     (if any), as available in {@link Document#connection()}
     <p>You can then set up other options (like user-agent, timeout, cookies), then execute it.</p>

     @return a connection prepared from the values of this form, in the same session as the one used to request it
     @throws IllegalArgumentException if the form's absolute action URL cannot be determined. Make sure you pass the
     document's base URI when parsing.
     */
    public Connection submit() {
        String action = hasAttr("action") ? absUrl("action") : baseUri();
        Validate.notEmpty(action, "Could not determine a form action URL for submit. Ensure you set a base URI when parsing.");
        Connection.Method method = attr("method").equalsIgnoreCase("POST") ?
                Connection.Method.POST : Connection.Method.GET;

        Document owner = ownerDocument();
        Connection connection = owner != null? owner.connection().newRequest() : Jsoup.newSession();
        return connection.url(action)
                .data(formData())
                .method(method);
    }

    /**
     * Get the data that this form submits. The returned list is a copy of the data, and changes to the contents of the
     * list will not be reflected in the DOM.
     * @return a list of key vals
     */
    public List<Connection.KeyVal> formData() {
        ArrayList<Connection.KeyVal> data = new ArrayList<>();

        // iterate the form control elements and accumulate their values
        Elements formEls = elements();
        for (Element el: formEls) {
            if (!el.tag().isFormSubmittable()) continue; // contents are form listable, superset of submitable
            if (el.hasAttr("disabled")) continue; // skip disabled form inputs
            String name = el.attr("name");
            if (name.length() == 0) continue;
            String type = el.attr("type");

            if (type.equalsIgnoreCase("button") || type.equalsIgnoreCase("image")) continue; // browsers don't submit these

            if (el.nameIs("select")) {
                Elements options = el.select("option[selected]");
                boolean set = false;
                for (Element option: options) {
                    data.add(HttpConnection.KeyVal.create(name, option.val()));
                    set = true;
                }
                if (!set) {
                    Element option = el.selectFirst("option");
                    if (option != null)
                        data.add(HttpConnection.KeyVal.create(name, option.val()));
                }
            } else if ("checkbox".equalsIgnoreCase(type) || "radio".equalsIgnoreCase(type)) {
                // only add checkbox or radio if they have the checked attribute
                if (el.hasAttr("checked")) {
                    final String val = el.val().length() >  0 ? el.val() : "on";
                    data.add(HttpConnection.KeyVal.create(name, val));
                }
            } else {
                data.add(HttpConnection.KeyVal.create(name, el.val()));
            }
        }
        return data;
    }

    @Override
    public FormElement clone() {
        return (FormElement) super.clone();
    }

    /**
     Performs an offline preflight check of a form submission, as if the given submitter element had been clicked. The
     successful controls of this form are computed (in document order, retaining duplicate names), and the supported
     HTML constraint validations are executed against them. No network request is made.
     <p>The supported constraint subset is: {@code required}, {@code pattern}, {@code min}, {@code max}, {@code step}
     (for {@code number} and {@code range} inputs, including decimal steps), and {@code minlength} / {@code maxlength}
     (for text-like inputs and textareas). Unknown input types fall back to {@code text} semantics, per the HTML
     standard's missing value default.</p>
     <p>Controls that are disabled, or that are descendants of a disabled {@code fieldset} (other than those within its
     first {@code legend} child), are skipped. Only the supplied submitter (if any) contributes a submit-button entry.
     Controls associated with this form via the {@code form} attribute are included. If the submitter is an
     {@code image} input, its click coordinates default to {@code 0,0}; use
     {@link #preflight(Element, int, int)} to supply explicit coordinates.</p>

     @param submitter the submit button that initiated the submission, or {@code null} for none
     @return the preflight result, holding the field pairs and any constraint validation errors
     @see #preflight(Element, int, int)
     */
    public PreflightResult preflight(@Nullable Element submitter) {
        return preflight(submitter, 0, 0);
    }

    /**
     Performs an offline preflight check of a form submission, with explicit image-button click coordinates. As
     {@link #preflight(Element)}, but if the submitter is an {@code <input type=image>}, the supplied coordinates are
     submitted as {@code name.x} and {@code name.y}.

     @param submitter the submit button that initiated the submission, or {@code null} for none
     @param imageX the x coordinate of the click on an image submitter; must not be negative
     @param imageY the y coordinate of the click on an image submitter; must not be negative
     @return the preflight result, holding the field pairs and any constraint validation errors
     @throws IllegalArgumentException if either image coordinate is negative
     */
    public PreflightResult preflight(@Nullable Element submitter, int imageX, int imageY) {
        Validate.isTrue(imageX >= 0 && imageY >= 0, "Image submitter coordinates must not be negative");

        ArrayList<Connection.KeyVal> data = new ArrayList<>();
        ArrayList<PreflightError> errors = new ArrayList<>();

        for (Element el : preflightControls()) {
            if (!isSubmittableTag(el)) continue; // contents are form listable, superset of submittable
            if (isDisabled(el)) continue; // skip disabled controls, incl. those in a disabled fieldset
            String name = el.attr("name");
            String type = el.attr("type").toLowerCase(Locale.ROOT);
            boolean isSubmitter = el == submitter;

            if (el.nameIs("input")) {
                switch (type) {
                    case "submit":
                        if (isSubmitter && !name.isEmpty())
                            data.add(HttpConnection.KeyVal.create(name, el.val()));
                        break;
                    case "button":
                    case "reset":
                        break; // never successful
                    case "image":
                        if (isSubmitter) {
                            String prefix = name.isEmpty() ? "" : name + ".";
                            data.add(HttpConnection.KeyVal.create(prefix + "x", String.valueOf(imageX)));
                            data.add(HttpConnection.KeyVal.create(prefix + "y", String.valueOf(imageY)));
                        }
                        break;
                    case "checkbox":
                    case "radio":
                        if (el.hasAttr("checked") && !name.isEmpty()) {
                            String val = el.val().length() > 0 ? el.val() : "on";
                            data.add(HttpConnection.KeyVal.create(name, val));
                        }
                        break;
                    default: // text and unknown types (missing value default is text)
                        if (!name.isEmpty())
                            data.add(HttpConnection.KeyVal.create(name, el.val()));
                }
            } else if (el.nameIs("button")) {
                String buttonType = type.isEmpty() ? "submit" : type; // missing value default for button is submit
                if (isSubmitter && buttonType.equals("submit") && !name.isEmpty())
                    data.add(HttpConnection.KeyVal.create(name, el.val()));
            } else if (el.nameIs("select")) {
                if (!name.isEmpty()) {
                    Elements options = el.select("option[selected]");
                    boolean set = false;
                    for (Element option : options) {
                        data.add(HttpConnection.KeyVal.create(name, option.val()));
                        set = true;
                    }
                    if (!set) {
                        Element option = el.selectFirst("option");
                        if (option != null)
                            data.add(HttpConnection.KeyVal.create(name, option.val()));
                    }
                }
            } else { // textarea, keygen, object
                if (!name.isEmpty())
                    data.add(HttpConnection.KeyVal.create(name, el.val()));
            }

            validateControl(el, type, errors);
        }
        return new PreflightResult(data, errors);
    }

    /**
     Performs an offline preflight check of a form submission with no submitter. As {@link #preflight(Element)}.
     @return the preflight result, holding the field pairs and any constraint validation errors
     */
    public PreflightResult preflight() {
        return preflight(null, 0, 0);
    }

    /**
     Gets the submittable controls associated with this form, in document order. Includes controls associated via the
     {@code form} attribute, and controls linked to this form during the parse.
     */
    private Elements preflightControls() {
        Document owner = ownerDocument();
        if (owner == null) { // detached form; union children and parse-linked controls
            Elements els = select(preflightable);
            for (Element linkedEl : linkedEls) {
                if (!els.contains(linkedEl)) els.add(linkedEl);
            }
            return els;
        }
        Elements controls = new Elements();
        String id = id();
        for (Element el : owner.select(preflightable)) {
            String formAttr = el.attr("form");
            if (!formAttr.isEmpty()) {
                if (!id.isEmpty() && formAttr.equals(id)) controls.add(el); // associated via the form attribute
            } else if (isWithin(el, this) || linkedEls.contains(el)) {
                controls.add(el);
            }
        }
        return controls;
    }

    private static boolean isSubmittableTag(Element el) {
        return el.tag().isFormSubmittable() || el.nameIs("button");
    }

    /** Test if el is, or is a descendant of, ancestor. */
    private static boolean isWithin(Element el, Element ancestor) {
        for (Element p = el; p != null; p = p.parent()) {
            if (p == ancestor) return true;
        }
        return false;
    }

    /**
     Test if a control is disabled: either directly, or by being a descendant of a disabled fieldset, unless it is
     within that fieldset's first legend child.
     */
    private static boolean isDisabled(Element el) {
        if (el.hasAttr("disabled")) return true;
        for (Element p = el.parent(); p != null; p = p.parent()) {
            if (p.nameIs("fieldset") && p.hasAttr("disabled")) {
                Element legend = firstLegendChild(p);
                if (legend != null && isWithin(el, legend)) continue; // first legend contents remain enabled
                return true;
            }
        }
        return false;
    }

    private static @Nullable Element firstLegendChild(Element fieldset) {
        for (Element child : fieldset.children()) {
            if (child.nameIs("legend")) return child;
        }
        return null;
    }

    /**
     Runs the supported HTML constraint validations (required, pattern, min, max, step, minlength, maxlength) against a
     control, appending any failures to errors.
     */
    private static void validateControl(Element el, String type, List<PreflightError> errors) {
        // controls barred from constraint validation
        if (el.nameIs("button") || el.nameIs("object") || el.nameIs("keygen")) return;
        boolean isInput = el.nameIs("input");
        if (isInput && (type.equals("hidden") || type.equals("submit") || type.equals("reset")
            || type.equals("button") || type.equals("image"))) return;

        boolean checkable = isInput && (type.equals("checkbox") || type.equals("radio"));
        boolean numeric = isInput && (type.equals("number") || type.equals("range"));
        boolean textLike = el.nameIs("textarea") || (isInput && !checkable && !numeric && !type.equals("file"));

        if (el.hasAttr("required")) {
            boolean missing;
            if (checkable) {
                missing = !el.hasAttr("checked");
            } else if (el.nameIs("select")) {
                Element selected = el.selectFirst("option[selected]");
                if (selected == null) selected = el.selectFirst("option");
                missing = selected == null || selected.val().isEmpty();
            } else {
                missing = el.val().isEmpty();
            }
            if (missing)
                errors.add(new PreflightError(el, "required", "Required control has no value"));
        }

        if (textLike) {
            String val = el.val();
            if (el.hasAttr("pattern") && !val.isEmpty()) {
                try {
                    if (!Pattern.compile("^(?:" + el.attr("pattern") + ")$").matcher(val).matches())
                        errors.add(new PreflightError(el, "pattern",
                            "Value does not match the pattern " + el.attr("pattern")));
                } catch (PatternSyntaxException ignored) {
                    // an invalid pattern attribute is not a constraint violation
                }
            }
            int length = val.codePointCount(0, val.length());
            Integer minlength = parseInt(el.attr("minlength"));
            if (minlength != null && length < minlength)
                errors.add(new PreflightError(el, "minlength",
                    "Value length " + length + " is less than minlength " + minlength));
            Integer maxlength = parseInt(el.attr("maxlength"));
            if (maxlength != null && length > maxlength)
                errors.add(new PreflightError(el, "maxlength",
                    "Value length " + length + " exceeds maxlength " + maxlength));
        }

        if (numeric) {
            String val = el.val();
            if (!val.isEmpty()) {
                Double num = parseDouble(val);
                if (num == null) {
                    errors.add(new PreflightError(el, "number", "Value is not a valid number"));
                } else {
                    Double min = parseDouble(el.attr("min"));
                    if (min != null && num < min)
                        errors.add(new PreflightError(el, "min", "Value " + val + " is less than min " + el.attr("min")));
                    Double max = parseDouble(el.attr("max"));
                    if (max != null && num > max)
                        errors.add(new PreflightError(el, "max", "Value " + val + " exceeds max " + el.attr("max")));
                    String stepAttr = el.attr("step");
                    if (!stepAttr.equalsIgnoreCase("any")) {
                        Double step = parseDouble(stepAttr);
                        double stepVal = step != null && step > 0 ? step : 1.0;
                        double base = min != null ? min : 0.0;
                        double ratio = (num - base) / stepVal;
                        if (Math.abs(ratio - Math.rint(ratio)) > 1e-8)
                            errors.add(new PreflightError(el, "step",
                                "Value " + val + " is not aligned to step " + stepAttr + " from " + base));
                    }
                }
            }
        }
    }

    private static @Nullable Double parseDouble(String s) {
        if (s.isEmpty()) return null;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static @Nullable Integer parseInt(String s) {
        if (s.isEmpty()) return null;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     The result of an offline form submission preflight, as computed by {@link #preflight(Element)}. Holds the field
     pairs that would be submitted (in document order, retaining duplicate names), and any HTML constraint validation
     errors found. The submission should be considered blocked if {@link #isValid()} is {@code false}.
     */
    public static class PreflightResult {
        private final List<Connection.KeyVal> data;
        private final List<PreflightError> errors;

        PreflightResult(List<Connection.KeyVal> data, List<PreflightError> errors) {
            this.data = data;
            this.errors = errors;
        }

        /**
         Test if the preflight passed all supported constraint validations.
         @return true if there were no validation errors and the submission may proceed
         */
        public boolean isValid() {
            return errors.isEmpty();
        }

        /**
         Get the field pairs that would be submitted, in document order. Duplicate field names are retained.
         @return the list of key vals
         */
        public List<Connection.KeyVal> data() {
            return data;
        }

        /**
         Get the constraint validation errors found, in document order of their controls.
         @return the list of preflight errors
         */
        public List<PreflightError> errors() {
            return errors;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Fields:\n");
            for (Connection.KeyVal kv : data)
                sb.append("  ").append(kv.key()).append("=").append(kv.value()).append("\n");
            sb.append(isValid() ? "Valid" : "Errors:").append("\n");
            for (PreflightError error : errors)
                sb.append("  ").append(error).append("\n");
            return sb.toString();
        }
    }

    /**
     A constraint validation failure found during a form submission preflight. Carries the failing control, the
     constraint that failed, a message, and the control's source position (if tracked during the parse).
     */
    public static class PreflightError {
        private final Element element;
        private final String constraint;
        private final String message;

        PreflightError(Element element, String constraint, String message) {
            this.element = element;
            this.constraint = constraint;
            this.message = message;
        }

        /**
         Get the control element that failed validation.
         @return the failing element
         */
        public Element element() {
            return element;
        }

        /**
         Get the name of the constraint that failed (e.g. {@code required}, {@code pattern}, {@code min}, {@code max},
         {@code step}, {@code minlength}, {@code maxlength}, or {@code number} for an unparsable numeric value).
         @return the constraint name
         */
        public String constraint() {
            return constraint;
        }

        /**
         Get a description of the validation failure.
         @return the failure message
         */
        public String message() {
            return message;
        }

        /**
         Get the source position of the failing control, if position tracking was enabled during the parse.
         @return the start position of the element in its source; untracked positions report {@code -1} coordinates
         */
        public Range.Position position() {
            return element.sourceRange().start();
        }

        @Override
        public String toString() {
            Range.Position pos = position();
            return constraint + " at line " + pos.lineNumber() + ", column " + pos.columnNumber()
                + " <" + element.tagName() + " name=" + element.attr("name") + ">: " + message;
        }
    }
}
