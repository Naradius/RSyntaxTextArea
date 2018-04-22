/*
 * 10/16/2004
 *
 * RSyntaxDocument.java - A document capable of syntax highlighting, used by
 * RSyntaxTextArea.
 *
 * This library is distributed under a modified BSD license.  See the included
 * RSyntaxTextArea.License.txt file for details.
 */
package org.fife.ui.rsyntaxtextarea;

import java.awt.event.ActionEvent;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.Iterator;

import javax.swing.Action;
import javax.swing.event.DocumentEvent;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.Segment;

import org.fife.ui.rsyntaxtextarea.modes.AbstractMarkupTokenMaker;
import org.fife.ui.rtextarea.RDocument;
import org.fife.util.DynamicIntArray;
import org.fife.util.DynamicTokenArray;


/**
 * The document used by {@link org.fife.ui.rsyntaxtextarea.RSyntaxTextArea}.
 * This document is like <code>javax.swing.text.PlainDocument</code> except that
 * it also keeps track of syntax highlighting in the document.  It has a "style"
 * attribute associated with it that determines how syntax highlighting is done
 * (i.e., what language is being highlighted).<p>
 *
 * Instances of <code>RSyntaxTextArea</code> will only accept instances of
 * <code>RSyntaxDocument</code>, since it is this document that keeps
 * track of syntax highlighting.  All others will cause an exception to be
 * thrown.<p>
 *
 * To change the language being syntax highlighted at any time, you merely have
 * to call {@link #setSyntaxStyle}.  Other than that, this document can be
 * treated like any other save one caveat:  all <code>DocumentEvent</code>s of
 * type <code>CHANGE</code> use their offset and length values to represent the
 * first and last lines, respectively, that have had their syntax coloring
 * change.  This is really a hack to increase the speed of the painting code
 * and should really be corrected, but oh well.
 *
 * @author Robert Futrell
 * @version 1.0
 */
public class RSyntaxDocument extends RDocument implements Iterable<Token>,
		SyntaxConstants {

	/**
	 * Creates a {@link TokenMaker} appropriate for a given programming
	 * language.
	 */
	private transient TokenMakerFactory tokenMakerFactory;

	/**
	 * Splits text into tokens for the current programming language.
	 */
	private transient TokenMaker tokenMaker;

	/**
	 * The current syntax style.  Only cached to keep this class serializable.
	 */
	private String syntaxStyle;

	private transient int lastLine = -1;
	private transient Token cachedTokenList;
	private transient int useCacheCount = 0;
	private transient int tokenRetrievalCount = 0;

	private transient Segment s;

	/**
	 * If this is set to <code>true</code>, debug information about how much
	 * token caching is helping is printed to stdout.
	 */
	private static final boolean DEBUG_TOKEN_CACHING = false;


	/**
	 * Constructs a plain text document.  A default root element is created,
	 * and the tab size set to 5.
	 *
	 * @param syntaxStyle The syntax highlighting scheme to use.
	 */
	public RSyntaxDocument(String syntaxStyle) {
		this(null, syntaxStyle);
	}


	/**
	 * Constructs a plain text document.  A default root element is created,
	 * and the tab size set to 5.
	 *
	 * @param tmf The <code>TokenMakerFactory</code> for this document.  If
	 *        this is <code>null</code>, a default factory is used.
	 * @param syntaxStyle The syntax highlighting scheme to use.
	 */
	public RSyntaxDocument(TokenMakerFactory tmf, String syntaxStyle) {
		putProperty(tabSizeAttribute, Integer.valueOf(5));
		s = new Segment();
		setTokenMakerFactory(tmf);
		setSyntaxStyle(syntaxStyle);
	}

	/**
	 * Returns the closest {@link TokenTypes "standard" token type} for a given
	 * "internal" token type (e.g. one whose value is <code>&lt; 0</code>).
	 *
	 * @param type The token type.
	 * @return The closest "standard" token type.  If a mapping is not defined
	 *         for this language, then <code>type</code> is returned.
	 */
	public int getClosestStandardTokenTypeForInternalType(int type) {
		return tokenMaker.getClosestStandardTokenTypeForInternalType(type);
	}


	/**
	 * Returns whether closing markup tags should be automatically completed.
	 * This method only returns <code>true</code> if
	 * {@link #getLanguageIsMarkup()} also returns <code>true</code>.
	 *
	 * @return Whether markup closing tags should be automatically completed.
	 * @see #getLanguageIsMarkup()
	 */
	public boolean getCompleteMarkupCloseTags() {
		// TODO: Remove terrible dependency on AbstractMarkupTokenMaker
		return getLanguageIsMarkup() &&
				((AbstractMarkupTokenMaker)tokenMaker).getCompleteCloseTags();
	}


	/**
	 * Returns whether the current programming language uses curly braces
	 * ('<code>{</code>' and '<code>}</code>') to denote code blocks.
	 *
	 * @param languageIndex The language index at the offset in question.
	 *        Since some <code>TokenMaker</code>s effectively have nested
	 *        languages (such as JavaScript in HTML), this parameter tells the
	 *        <code>TokenMaker</code> what sub-language to look at.
	 * @return Whether curly braces denote code blocks.
	 */
	public boolean getCurlyBracesDenoteCodeBlocks(int languageIndex) {
		return tokenMaker.getCurlyBracesDenoteCodeBlocks(languageIndex);
	}


	/**
	 * Returns whether the current language is a markup language, such as
	 * HTML, XML or PHP.
	 *
	 * @return Whether the current language is a markup language.
	 */
	public boolean getLanguageIsMarkup() {
		return tokenMaker.isMarkupLanguage();
	}

	/**
	 * Returns the text to place at the beginning and end of a
	 * line to "comment" it in this programming language.
	 *
	 * @return The start and end strings to add to a line to "comment"
	 *         it out.  A <code>null</code> value for either means there
	 *         is no string to add for that part.  A value of
	 *         <code>null</code> for the array means this language
	 *         does not support commenting/uncommenting lines.
	 */
	public String[] getLineCommentStartAndEnd(int languageIndex) {
		return tokenMaker.getLineCommentStartAndEnd(languageIndex);
	}


	/**
	 * Returns whether tokens of the specified type should have "mark
	 * occurrences" enabled for the current programming language.
	 *
	 * @param type The token type.
	 * @return Whether tokens of this type should have "mark occurrences"
	 *         enabled.
	 */
	boolean getMarkOccurrencesOfTokenType(int type) {
		return tokenMaker.getMarkOccurrencesOfTokenType(type);
	}


	/**
	 * Returns the occurrence marker for the current language.
	 *
	 * @return The occurrence marker.
	 */
	OccurrenceMarker getOccurrenceMarker() {
		return tokenMaker.getOccurrenceMarker();
	}


	/**
	 * This method returns whether auto indentation should be done if Enter
	 * is pressed at the end of the specified line.
	 *
	 * @param line The line to check.
	 * @return Whether an extra indentation should be done.
	 */
	public boolean getShouldIndentNextLine(int line) {
		Token t = getTokenListForLine(line);
		t = t.getLastNonCommentNonWhitespaceToken();
		return tokenMaker.getShouldIndentNextLineAfter(t);
	}


	/**
	 * Returns the syntax style being used.
	 *
	 * @return The syntax style.
	 * @see #setSyntaxStyle(String)
	 */
	public String getSyntaxStyle() {
		return syntaxStyle;
	}


	/**
	 * Returns a token list for the specified segment of text representing
	 * the specified line number.  This method is basically a wrapper for
	 * <code>tokenMaker.getTokenList</code> that takes into account the last
	 * token on the previous line to assure token accuracy.
	 *
	 * @param line The line number of <code>text</code> in the document,
	 *        &gt;= 0.
	 * @return A token list representing the specified line.
	 */
	public final Token getTokenListForLine(int line) {

		tokenRetrievalCount++;

		Element map = getDefaultRootElement();
		Element elem = map.getElement(line);
		int startOffset = map.getStartOffset();
		int lineOffset = elem.getStartOffset();
		int endOffset = map.getEndOffset() - 1; // Why always "-1"?
		try {
			getText(startOffset,endOffset-startOffset, s);
		} catch (BadLocationException ble) {
			ble.printStackTrace();
			return null;
		}

		Token list = tokenMaker.getTokenList(s, null, startOffset);
		Token token = list;
		/*System.out.println("------------------------------");
		while (token != null) {
			System.out.println("");
			System.out.println(token);
			System.out.println("OFFSET:" + token.getOffset());
			System.out.println("TEXT:" + token.getTextOffset());
			System.out.println("LINE:" + elem.getStartOffset());
			System.out.println(token.containsPosition(elem.getStartOffset()));
			token = token.getNextToken();
		}*/
		Token returntoken = (list != null) ? ((list.getType() == Token.NULL) ? list : RSyntaxUtilities.getTokenAtOffset(list, lineOffset)) : null;
		//System.out.println(returntoken);
		return returntoken;
	}


	boolean insertBreakSpecialHandling(ActionEvent e) {
		Action a = tokenMaker.getInsertBreakAction();
		if (a!=null) {
			a.actionPerformed(e);
			return true;
		}
		return false;
	}


	/**
	 * Returns whether a character could be part of an "identifier" token
	 * in a specific language.  This is used to identify such things as the
	 * bounds of the "word" to select on double-clicking.
	 *
	 * @param languageIndex The language index the character was found in.
	 * @param ch The character.
	 * @return Whether the character could be part of an "identifier" token.
	 */
	public boolean isIdentifierChar(int languageIndex, char ch) {
		return tokenMaker.isIdentifierChar(languageIndex, ch);
	}


	/**
	 * Returns an iterator over the paintable tokens in this document.  Results
	 * are undefined if this document is modified while the iterator is being
	 * iterated through, so this should only be used on the EDT.<p>
	 *
	 * The <code>remove()</code> method of the returned iterator will throw
	 * an <code>UnsupportedOperationException</code>.
	 *
	 * @return An iterator.
	 */
	@Override
	public Iterator<Token> iterator() {
		return new TokenIterator(this);
	}


	/**
	 * Deserializes a document.
	 *
	 * @param in The stream to read from.
	 * @throws ClassNotFoundException
	 * @throws IOException
	 */
	private void readObject(ObjectInputStream in)
						throws ClassNotFoundException, IOException {

		in.defaultReadObject();

		// Install default TokenMakerFactory.  To support custom TokenMakers,
		// both JVM's should install default TokenMakerFactories that support
		// the language they want to use beforehand.
		setTokenMakerFactory(null);

		// Handle other transient stuff
		this.s = new Segment();
		int lineCount = getDefaultRootElement().getElementCount();
		setSyntaxStyle(syntaxStyle); // Actually install (transient) TokenMaker

	}


	/**
	 * Makes our private <code>Segment s</code> point to the text in our
	 * document referenced by the specified element.  Note that
	 * <code>line</code> MUST be a valid line number in the document.
	 *
	 * @param line The line number you want to get.
	 */
	private void setSharedSegment(int line) {

		Element map = getDefaultRootElement();
		//int numLines = map.getElementCount();

		Element element = map.getElement(line);
		if (element==null) {
			throw new InternalError("Invalid line number: " + line);
		}
		int startOffset = element.getStartOffset();
		//int endOffset = (line==numLines-1 ?
		//			element.getEndOffset()-1 : element.getEndOffset() - 1);
		int endOffset = element.getEndOffset()-1; // Why always "-1"?
		try {
			getText(startOffset, endOffset-startOffset, s);
		} catch (BadLocationException ble) {
			throw new InternalError("Text range not in document: " +
								startOffset + "-" + endOffset);
		}

	}


	/**
	 * Sets the syntax style being used for syntax highlighting in this
	 * document.  What styles are supported by a document is determined by its
	 * {@link TokenMakerFactory}.  By default, all <code>RSyntaxDocument</code>s
	 * support all languages built into <code>RSyntaxTextArea</code>.
	 *
	 * @param styleKey The new style to use, such as
	 *        {@link SyntaxConstants#SYNTAX_STYLE_JAVA}.  If this style is not
	 *        known or supported by this document, then
	 *        {@link SyntaxConstants#SYNTAX_STYLE_NONE} is used.
	 * @see #setSyntaxStyle(TokenMaker)
	 * @see #getSyntaxStyle()
	 */
	public void setSyntaxStyle(String styleKey) {
		tokenMaker = tokenMakerFactory.getTokenMaker(styleKey);
		tokenMaker.setDocument(this);
		updateSyntaxHighlightingInformation();
		this.syntaxStyle = styleKey;
	}


	/**
	 * Sets the syntax style being used for syntax highlighting in this
	 * document.  You should call this method if you've created a custom token
	 * maker for a language not normally supported by
	 * <code>RSyntaxTextArea</code>.
	 *
	 * @param tokenMaker The new token maker to use.
	 * @see #setSyntaxStyle(String)
	 */
	public void setSyntaxStyle(TokenMaker tokenMaker) {
		this.tokenMaker = tokenMaker;
		tokenMaker.setDocument(this);
		updateSyntaxHighlightingInformation();
		this.syntaxStyle = "text/unknown"; // TODO: Make me public?
	}


	/**
	 * Sets the token maker factory used by this document.
	 *
	 * @param tmf The <code>TokenMakerFactory</code> for this document.  If
	 *        this is <code>null</code>, a default factory is used.
	 */
	public void setTokenMakerFactory(TokenMakerFactory tmf) {
		tokenMakerFactory = tmf!=null ? tmf :
			TokenMakerFactory.getDefaultInstance();
	}

	/**
	 * Updates internal state information; e.g. the "last tokens on lines"
	 * data.  After this, a changed update is fired to let listeners know that
	 * the document's structure has changed.<p>
	 *
	 * This is called internally whenever the syntax style changes.
	 */
	private void updateSyntaxHighlightingInformation() {

		// Reinitialize the "last token on each line" array.  Note that since
		// the actual text in the document isn't changing, the number of lines
		// is the same.
		Element map = getDefaultRootElement();
		int numLines = map.getElementCount();

		// Let everybody know that syntax styles have (probably) changed.
		fireChangedUpdate(new DefaultDocumentEvent(
						0, numLines-1, DocumentEvent.EventType.CHANGE));

	}


}