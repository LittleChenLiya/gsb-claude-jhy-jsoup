package org.jsoup.nodes;

/**
 Thrown when a {@link SourceEditor} request or a {@link SourceEditPlan} commit cannot be performed safely against the
 original source. Causes include:
 <ul>
 <li>overlapping (conflicting) edit ranges;</li>
 <li>nodes without reliable tracked source positions (position tracking disabled, or implicitly created nodes);</li>
 <li>attributes that appear more than once in the same start tag (ambiguous source span);</li>
 <li>a source text that no longer matches the digest captured when the plan was created.</li>
 </ul>
 @see SourceEditor
 @since 1.24.1
 */
public class SourceEditException extends RuntimeException {
    public SourceEditException(String message) {
        super(message);
    }
}
