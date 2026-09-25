package org.jsoup.nodes;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the offline form submission preflight in {@link FormElement}.
 */
public class FormElementPreflightTest {
    private static List<String> pairs(List<Connection.KeyVal> data) {
        List<String> pairs = new ArrayList<>();
        for (Connection.KeyVal kv : data)
            pairs.add(kv.key() + "=" + kv.value());
        return pairs;
    }

    private static FormElement form(String html) {
        Document doc = Jsoup.parse(html, "https://example.com/");
        return (FormElement) doc.selectFirst("form");
    }

    private static Document loadFixture() throws IOException, URISyntaxException {
        URL url = FormElementPreflightTest.class.getResource("/htmltests/form-preflight.html");
        assertNotNull(url);
        File in = new File(url.toURI());
        return Jsoup.parse(in, "UTF-8", "https://example.com/", Parser.htmlParser().setTrackPosition(true));
    }

    @Test public void submitterChoiceChangesFields() {
        FormElement form = form("<form><input name=q value=x>" +
            "<input type=submit name=save value=Save><input type=submit name=delete value=Delete></form>");
        Element save = form.selectFirst("input[name=save]");
        Element delete = form.selectFirst("input[name=delete]");

        assertEquals(List.of("q=x", "save=Save"), pairs(form.preflight(save).data()));
        assertEquals(List.of("q=x", "delete=Delete"), pairs(form.preflight(delete).data()));
        assertEquals(List.of("q=x"), pairs(form.preflight().data())); // no submitter, no submit entries
        assertEquals(List.of("q=x"), pairs(form.preflight(null).data()));
    }

    @Test public void duplicateCheckboxesRetained() {
        FormElement form = form("<form><input type=checkbox name=t value=a checked>" +
            "<input type=checkbox name=t value=b checked><input type=checkbox name=t value=c></form>");
        assertEquals(List.of("t=a", "t=b"), pairs(form.preflight().data()));
    }

    @Test public void disabledFieldsetSkippedExceptFirstLegend() {
        FormElement form = form("<form><fieldset disabled>" +
            "<legend><input name=legend-note value=enabled></legend>" +
            "<input name=locked value=nope></fieldset>" +
            "<input name=plain value=yes disabled><input name=free value=ok></form>");
        assertEquals(List.of("legend-note=enabled", "free=ok"), pairs(form.preflight().data()));
    }

    @Test public void requiredEmptyBlocksPreflight() {
        Document doc = Jsoup.parse("<form>\n<input name=note value=\"\" required>\n</form>",
            Parser.htmlParser().setTrackPosition(true));
        FormElement form = (FormElement) doc.selectFirst("form");
        FormElement.PreflightResult result = form.preflight();

        assertFalse(result.isValid());
        assertEquals(1, result.errors().size());
        FormElement.PreflightError error = result.errors().get(0);
        assertEquals("required", error.constraint());
        assertEquals("note", error.element().attr("name"));
        assertEquals(2, error.position().lineNumber());
        assertEquals(1, error.position().columnNumber());
    }

    @Test public void preflightMatchesFormDataSequence() {
        // with no submitter and no submit buttons, the preflight field sequence matches formData()
        String html = "<form><input name=one value=two><select name=three><option value=not>" +
            "<option value=four selected><option value=five selected></select><textarea name=six>seven</textarea>" +
            "<input name=seven type=radio value=on checked><input name=eight type=checkbox checked>" +
            "<input name=ten value=text disabled></form>";
        FormElement form = form(html);
        assertEquals(pairs(form.formData()), pairs(form.preflight().data()));
    }

    @Test public void decimalStepAndMinMax() {
        FormElement ok = form("<form><input type=number name=n value=2.5 min=0 max=10 step=0.5></form>");
        assertTrue(ok.preflight().isValid());

        FormElement stepOff = form("<form><input type=number name=n value=2.3 min=0 max=10 step=0.5></form>");
        assertEquals("step", stepOff.preflight().errors().get(0).constraint());

        FormElement overMax = form("<form><input type=number name=n value=11 min=0 max=10 step=0.5></form>");
        assertEquals("max", overMax.preflight().errors().get(0).constraint());

        FormElement underMin = form("<form><input type=number name=n value=-1 min=0 max=10 step=0.5></form>");
        assertEquals("min", underMin.preflight().errors().get(0).constraint());

        FormElement notANumber = form("<form><input type=number name=n value=abc></form>");
        assertEquals("number", notANumber.preflight().errors().get(0).constraint());
    }

    @Test public void multipleSelectSubmitsAllSelected() {
        FormElement form = form("<form><select name=sides multiple>" +
            "<option value=fries selected><option value=salad selected><option value=soup>" +
            "</select></form>");
        assertEquals(List.of("sides=fries", "sides=salad"), pairs(form.preflight().data()));
    }

    @Test public void externalFormAttributeControlsIncluded() {
        Document doc = Jsoup.parse("<form id=f><input name=inner value=1></form>" +
            "<input name=outer value=2 form=f><input name=other value=3>");
        FormElement form = (FormElement) doc.selectFirst("form");
        // document order, with the form-associated external control after the form's own
        assertEquals(List.of("inner=1", "outer=2"), pairs(form.preflight().data()));
    }

    @Test public void imageSubmitterCoordinates() {
        FormElement form = form("<form><input name=q value=x><input type=image name=pin src=p.png></form>");
        Element pin = form.selectFirst("input[type=image]");

        assertEquals(List.of("q=x", "pin.x=10", "pin.y=20"), pairs(form.preflight(pin, 10, 20).data()));
        // default coordinates are 0,0 when the caller does not supply them
        assertEquals(List.of("q=x", "pin.x=0", "pin.y=0"), pairs(form.preflight(pin).data()));
        // not the submitter: no image entries
        assertEquals(List.of("q=x"), pairs(form.preflight().data()));

        assertThrows(IllegalArgumentException.class, () -> form.preflight(pin, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> form.preflight(pin, 0, -5));
    }

    @Test public void unknownTypeFallsBackToText() {
        FormElement form = form("<form><input type=frobnicate name=x value=v1></form>");
        assertEquals(List.of("x=v1"), pairs(form.preflight().data()));

        // and text constraints (like pattern) apply to unknown types
        FormElement constrained = form("<form><input type=frobnicate name=x value=abc pattern=\"[0-9]+\"></form>");
        FormElement.PreflightResult result = constrained.preflight();
        assertFalse(result.isValid());
        assertEquals("pattern", result.errors().get(0).constraint());
    }

    @Test public void lengthConstraints() {
        FormElement form = form("<form><input name=a value=x minlength=2>" +
            "<textarea name=b maxlength=3>toolong</textarea></form>");
        FormElement.PreflightResult result = form.preflight();
        assertFalse(result.isValid());
        assertEquals("minlength", result.errors().get(0).constraint());
        assertEquals("maxlength", result.errors().get(1).constraint());
    }

    @Test public void fixturePreflight() throws IOException, URISyntaxException {
        Document doc = loadFixture();
        FormElement form = (FormElement) doc.selectFirst("form#order");
        Element save = form.selectFirst("input[value=save]");

        FormElement.PreflightResult result = form.preflight(save);
        assertEquals(List.of(
            "customer=jsoup", "zip=abc", "quantity=2.3", "note=",
            "topping=cheese", "topping=bacon",
            "sides=fries", "sides=salad",
            "legend-note=still enabled",
            "action=save",
            "external=linked"), pairs(result.data()));

        assertFalse(result.isValid());
        assertEquals(3, result.errors().size());
        assertEquals("pattern", result.errors().get(0).constraint());
        assertEquals("zip", result.errors().get(0).element().attr("name"));
        assertEquals("step", result.errors().get(1).constraint());
        assertEquals("required", result.errors().get(2).constraint());
        assertEquals(9, result.errors().get(2).position().lineNumber());
    }

    @Test public void printPreflightReport() throws IOException, URISyntaxException {
        // Prints the preflight fields and failing controls for the local fixture. Run with:
        // mvn -q -Dtest=FormElementPreflightTest#printPreflightReport test
        Document doc = loadFixture();
        FormElement form = (FormElement) doc.selectFirst("form#order");
        Element save = form.selectFirst("input[value=save]");
        FormElement.PreflightResult result = form.preflight(save);

        System.out.println("Preflight of " + form.attr("action") + " with submitter action=" + save.val());
        System.out.print(result);

        assertFalse(result.isValid());
        assertEquals(11, result.data().size());
        assertEquals(3, result.errors().size());
    }
}
