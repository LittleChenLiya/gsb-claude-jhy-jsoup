/**
 Heading outline extraction: builds a nested {@code h1}..{@code h6} outline from a content element without
 modifying the source document.
 <h2>Usage</h2>
 <pre>{@code
 Document doc = Jsoup.parse(html);
 HeadingOutline outline = HeadingOutline.extract(doc.body());

 for (Section root : outline.roots())
     System.out.println(root.level() + " " + root.id() + " " + root.text());

 System.out.println(outline);                 // indented tree view of the whole outline
 System.out.println(outline.idConflicts());   // headings sharing a duplicate id (DOM left untouched)
 }</pre>
 <p>Extraction rules:</p>
 <ul>
 <li>Only headings within the given root element are considered; headings inside {@code script}, {@code style},
 or {@code template} elements are excluded.</li>
 <li>A heading nests under the nearest preceding heading with a smaller level; skipped levels are not
 invented.</li>
 <li>Existing {@code id} attributes are preserved; headings without one receive a stable generated id
 ({@code outline-N}) in the report only — the DOM is never written to.</li>
 <li>Lists returned by this package are independent unmodifiable copies.</li>
 </ul>
 */
@NullMarked
package org.jsoup.outline;

import org.jspecify.annotations.NullMarked;
