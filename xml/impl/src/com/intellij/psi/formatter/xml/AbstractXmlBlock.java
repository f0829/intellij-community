package com.intellij.psi.formatter.xml;

import com.intellij.codeFormatting.general.FormatterUtil;
import com.intellij.formatting.*;
import com.intellij.lang.ASTNode;
import com.intellij.lang.Language;
import com.intellij.lang.LanguageFormatting;
import com.intellij.lang.StdLanguages;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.formatter.common.AbstractBlock;
import com.intellij.psi.impl.source.SourceTreeToPsiMap;
import com.intellij.psi.impl.source.jsp.jspJava.JspDeclaration;
import com.intellij.psi.impl.source.jsp.jspJava.JspScriptlet;
import com.intellij.psi.impl.source.jsp.jspJava.OuterLanguageElement;
import com.intellij.psi.impl.source.tree.LeafElement;
import com.intellij.psi.impl.source.tree.TreeElement;
import com.intellij.psi.impl.source.tree.TreeUtil;
import com.intellij.psi.jsp.JspElementType;
import com.intellij.psi.search.PsiElementProcessor;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.xml.XmlChildRole;
import com.intellij.psi.xml.XmlElement;
import com.intellij.psi.xml.XmlElementType;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;


public abstract class AbstractXmlBlock extends AbstractBlock {
  protected final XmlFormattingPolicy myXmlFormattingPolicy;
  public static final @NonNls String JSPX_DECLARATION_TAG_NAME = "jsp:declaration";
  public static final @NonNls String JSPX_SCRIPTLET_TAG_NAME = "jsp:scriptlet";
  private static final @NonNls String JSP_TAG_PREFIX = "jsp:";

  public AbstractXmlBlock(final ASTNode node,
                          final Wrap wrap,
                          final Alignment alignment,
                          final XmlFormattingPolicy policy) {
    super(node, wrap, alignment);
    myXmlFormattingPolicy = policy;
    if (node == null) {
      LOG.assertTrue(false);
    }
    if (node.getTreeParent() == null) {
      myXmlFormattingPolicy.setRootBlock(node, this);
    }
  }


  protected static WrapType getWrapType(final int type) {
    if (type == CodeStyleSettings.DO_NOT_WRAP) return WrapType.NONE;
    if (type == CodeStyleSettings.WRAP_ALWAYS) return WrapType.ALWAYS;
    if (type == CodeStyleSettings.WRAP_AS_NEEDED) return WrapType.NORMAL;
    return WrapType.CHOP_DOWN_IF_LONG;
  }

  protected Alignment chooseAlignment(final ASTNode child, final Alignment attrAlignment, final Alignment textAlignment) {
    if (myNode.getElementType() == XmlElementType.XML_TEXT) return getAlignment();
    final IElementType elementType = child.getElementType();
    if (elementType == XmlElementType.XML_ATTRIBUTE && myXmlFormattingPolicy.getShouldAlignAttributes()) return attrAlignment;
    if (elementType == XmlElementType.XML_TEXT && myXmlFormattingPolicy.getShouldAlignText()) return textAlignment;
    return null;
  }

  private Wrap getTagEndWrapping(final XmlTag parent) {
    return Wrap.createWrap(myXmlFormattingPolicy.getWrappingTypeForTagEnd(parent), true);
  }

  protected Wrap chooseWrap(final ASTNode child, final Wrap tagBeginWrap, final Wrap attrWrap, final Wrap textWrap) {
    if (myNode.getElementType() == XmlElementType.XML_TEXT) return textWrap;
    final IElementType elementType = child.getElementType();
    if (elementType == XmlElementType.XML_ATTRIBUTE) return attrWrap;
    if (elementType == XmlElementType.XML_START_TAG_START) return tagBeginWrap;
    if (elementType == XmlElementType.XML_END_TAG_START) {
      final PsiElement parent = SourceTreeToPsiMap.treeElementToPsi(child.getTreeParent());
      if (parent instanceof XmlTag) {
        final XmlTag tag = (XmlTag)parent;
        if (canWrapTagEnd(tag)) {
          return getTagEndWrapping(tag);
        }
      }
      return null;
    }
    if (elementType == XmlElementType.XML_TEXT || elementType == XmlElementType.XML_DATA_CHARACTERS) return textWrap;
    return null;
  }

  private static boolean canWrapTagEnd(final XmlTag tag) {
    final String name = tag.getName();
    return tag.getSubTags().length > 0 || StringUtil.startsWithIgnoreCase(name, JSP_TAG_PREFIX);
  }

  protected XmlTag getTag() {
    return getTag(myNode);
  }

  protected static XmlTag getTag(final ASTNode node) {
    final PsiElement element = SourceTreeToPsiMap.treeElementToPsi(node);
    if (element instanceof XmlTag) {
      return (XmlTag)element;
    }
    else {
      return null;
    }
  }

  protected Wrap createTagBeginWrapping(final XmlTag tag) {
    return Wrap.createWrap(myXmlFormattingPolicy.getWrappingTypeForTagBegin(tag), true);
  }

  protected
  @Nullable
  ASTNode processChild(List<Block> result,
                       final ASTNode child,
                       final Wrap wrap,
                       final Alignment alignment,
                       final Indent indent) {
    final Language myLanguage = myNode.getPsi().getLanguage();
    final PsiElement childPsi = child.getPsi();
    final Language childLanguage = childPsi.getLanguage();
    if (useMyFormatter(myLanguage, childLanguage, childPsi)) {

      if (canBeAnotherTreeTagStart(child)) {
        XmlTag tag = JspTextBlock.findXmlTagAt(child, child.getStartOffset());
        if (tag != null
            && containsTag(tag)
            && doesNotIntersectSubTagsWith(tag)) {
          ASTNode currentChild = createAnotherTreeTagBlock(result, child, tag, indent, wrap, alignment);

          if (currentChild == null) {
            return null;
          }

          while (currentChild != null && currentChild.getTreeParent() != myNode && currentChild.getTreeParent() != child.getTreeParent()) {
            currentChild = processAllChildrenFrom(result, currentChild, wrap, alignment, indent);
            if (currentChild != null && (currentChild.getTreeParent() == myNode || currentChild.getTreeParent() == child.getTreeParent())) {
              return currentChild;
            }
            if (currentChild != null) {
              currentChild = currentChild.getTreeParent();

            }
          }

          return currentChild;
        }
      }

      processSimpleChild(child, indent, result, wrap, alignment);
      return child;

    }
    else {
      return createAnotherLanguageBlockWrapper(childLanguage, child, result, indent);
    }
  }

  private boolean doesNotIntersectSubTagsWith(final PsiElement tag) {
    final TextRange tagRange = tag.getTextRange();
    final XmlTag[] subTags = getSubTags();
    for (XmlTag subTag : subTags) {
      final TextRange subTagRange = subTag.getTextRange();
      if (subTagRange.getEndOffset() < tagRange.getStartOffset()) continue;
      if (subTagRange.getStartOffset() > tagRange.getEndOffset()) return true;

      if (tagRange.getStartOffset() > subTagRange.getStartOffset() && tagRange.getEndOffset() < subTagRange.getEndOffset()) return false;
      if (tagRange.getEndOffset() > subTagRange.getStartOffset() && tagRange.getEndOffset() < subTagRange.getEndOffset()) return false;

    }
    return true;
  }

  private XmlTag[] getSubTags() {

    if (myNode instanceof XmlTag) {
      return ((XmlTag)myNode.getPsi()).getSubTags();
    }
    else if (myNode.getPsi() instanceof XmlElement) {
      return collectSubTags((XmlElement)myNode.getPsi());
    }
    else {
      return new XmlTag[0];
    }

  }

  private static XmlTag[] collectSubTags(final XmlElement node) {
    final List<XmlTag> result = new ArrayList<XmlTag>();
    node.processElements(new PsiElementProcessor() {
      public boolean execute(final PsiElement element) {
        if (element instanceof XmlTag) {
          result.add((XmlTag)element);
        }
        return true;
      }
    }, node);
    return result.toArray(new XmlTag[result.size()]);
  }

  private boolean containsTag(final PsiElement tag) {
    final ASTNode closingTagStart = XmlChildRole.CLOSING_TAG_START_FINDER.findChild(myNode);
    final ASTNode startTagStart = XmlChildRole.START_TAG_END_FINDER.findChild(myNode);

    if (closingTagStart == null && startTagStart == null) {
      return tag.getTextRange().getEndOffset() <= myNode.getTextRange().getEndOffset();
    }
    else if (closingTagStart == null) {
      return false;
    }
    else {
      return tag.getTextRange().getEndOffset() <= closingTagStart.getTextRange().getEndOffset();
    }
  }

  private ASTNode processAllChildrenFrom(final List<Block> result,
                                         final @NotNull ASTNode child,
                                         final Wrap wrap,
                                         final Alignment alignment,
                                         final Indent indent) {
    ASTNode resultNode = child;
    ASTNode currentChild = child.getTreeNext();
    while (currentChild != null && currentChild.getElementType() != XmlElementType.XML_END_TAG_START) {
      if (!FormatterUtil.containsWhiteSpacesOnly(currentChild)) {
        currentChild = processChild(result, currentChild, wrap, alignment, indent);
        resultNode = currentChild;
      }
      if (currentChild != null) {
        currentChild = currentChild.getTreeNext();
      }
    }
    return resultNode;
  }

  private void processSimpleChild(final ASTNode child,
                                  final Indent indent,
                                  final List<Block> result,
                                  final Wrap wrap,
                                  final Alignment alignment) {
    if (myXmlFormattingPolicy.processJsp() &&
        (child.getElementType() == JspElementType.JSP_XML_TEXT
         || child.getPsi() instanceof OuterLanguageElement)) {
      final Pair<PsiElement, Language> root = JspTextBlock.findPsiRootAt(child, myXmlFormattingPolicy.processJavaTree());
      if (root != null) {
        createJspTextNode(result, child, indent);
        return;
      }
    }

    if (isXmlTag(child)) {
      result.add(new XmlTagBlock(child, wrap, alignment, myXmlFormattingPolicy, indent != null ? indent : Indent.getNoneIndent()));
    } else if (child.getElementType() == XmlElementType.XML_DOCTYPE) {
      result.add(
        new XmlBlock(child, wrap, alignment, myXmlFormattingPolicy, indent, null) {
          protected Wrap getDefaultWrap(final ASTNode node) {
            final IElementType type = node.getElementType();
            return type == XmlElementType.XML_ATTRIBUTE_VALUE_TOKEN ? Wrap.createWrap(getWrapType(myXmlFormattingPolicy.getAttributesWrap()), false) : null;
          }
        }
      );
    }
    else {
      result.add(new XmlBlock(child, wrap, alignment, myXmlFormattingPolicy, indent, null));
    }
  }

  private ASTNode createAnotherLanguageBlockWrapper(final Language childLanguage,
                                                    final ASTNode child,
                                                    final List<Block> result,
                                                    final Indent indent) {
    final PsiElement childPsi = child.getPsi();
    final FormattingModelBuilder builder = LanguageFormatting.INSTANCE.forContext(childPsi);
    LOG.assertTrue(builder != null);
    final FormattingModel childModel = builder.createModel(childPsi, getSettings());
    result.add(new AnotherLanguageBlockWrapper(child,
                                               myXmlFormattingPolicy,
                                               childModel.getRootBlock(),
                                               indent));
    return child;
  }

  private ASTNode createAnotherTreeTagBlock(final List<Block> result,
                                            final ASTNode child,
                                            PsiElement tag,
                                            final Indent indent,
                                            final Wrap wrap, final Alignment alignment) {
    Indent childIndent = indent;

    if (myNode.getElementType() == XmlElementType.HTML_DOCUMENT
        && tag.getParent() instanceof XmlTag
        && myXmlFormattingPolicy.indentChildrenOf((XmlTag)tag.getParent())) {
      childIndent = Indent.getNormalIndent();
    }
    result.add(createAnotherTreeTagBlock(tag, childIndent));
    TextRange tagRange = tag.getTextRange();
    ASTNode currentChild = findChildAfter(child, tagRange.getEndOffset());
    TextRange childRange = currentChild != null ? currentChild.getTextRange():null;

    while (currentChild != null && childRange.getEndOffset() > tagRange.getEndOffset()) {
      PsiElement psiElement = JspTextBlock.findXmlTagAt(currentChild, tagRange.getEndOffset());
      if (psiElement != null) {
        if (psiElement instanceof XmlTag &&
            psiElement.getTextRange().getStartOffset() >= childRange.getStartOffset() &&
            containsTag(psiElement) && doesNotIntersectSubTagsWith(psiElement)) {
          result.add(createAnotherTreeTagBlock(psiElement, childIndent));
          currentChild = findChildAfter(currentChild, psiElement.getTextRange().getEndOffset());
          childRange = currentChild != null ? currentChild.getTextRange():null;
          tagRange = psiElement.getTextRange();
        }
        else {
          result
            .add(new XmlBlock(currentChild, wrap, alignment, myXmlFormattingPolicy, indent, new TextRange(tagRange.getEndOffset(),
                                                                                                          childRange.getEndOffset())));
          return currentChild;
        }
      }
      else {
        result
          .add(new XmlBlock(currentChild, wrap, alignment, myXmlFormattingPolicy, indent, new TextRange(tagRange.getEndOffset(),
                                                                                                        childRange.getEndOffset())));
        return currentChild;
      }
    }

    return currentChild;
  }

  private Block createAnotherTreeTagBlock(final PsiElement tag, final Indent childIndent) {
    if (isXmlTag(tag)) {
      return new XmlTagBlock(tag.getNode(), null, null, createPolicyFor(), childIndent);
    }
    else {
      return new XmlBlock(tag.getNode(), null, null, createPolicyFor(), childIndent, tag.getTextRange());
    }

  }

  private XmlFormattingPolicy createPolicyFor() {
    return myXmlFormattingPolicy;
  }

  private CodeStyleSettings getSettings() {
    return myXmlFormattingPolicy.getSettings();
  }

private boolean canBeAnotherTreeTagStart(final ASTNode child) {
    return myXmlFormattingPolicy.processJsp()
           && PsiUtil.getJspFile(myNode.getPsi()) != null
           && (isXmlTag(myNode) || myNode.getElementType() == XmlElementType.HTML_DOCUMENT || myNode.getPsi() instanceof PsiFile) &&
           (child.getElementType() == XmlElementType.XML_DATA_CHARACTERS || child.getElementType() == JspElementType.JSP_XML_TEXT ||
            child.getPsi() instanceof OuterLanguageElement);

  }
  protected static boolean isXmlTag(final ASTNode child) {
    return isXmlTag(child.getPsi());
  }

  protected static boolean isXmlTag(final PsiElement psi) {
    return psi instanceof XmlTag && !(psi instanceof JspScriptlet) && !(psi instanceof JspDeclaration);
  }

  private static boolean useMyFormatter(final Language myLanguage, final Language childLanguage, final PsiElement childPsi) {
    return myLanguage == childLanguage
           || childLanguage == StdLanguages.JAVA
           || childLanguage == StdLanguages.HTML
           || childLanguage == StdLanguages.XHTML
           || childLanguage == StdLanguages.XML
           || childLanguage == StdLanguages.JSP
           || childLanguage == StdLanguages.JSPX
           || LanguageFormatting.INSTANCE.forContext(childPsi) == null;
  }

  protected boolean isJspxJavaContainingNode(final ASTNode child) {
    if (child.getElementType() != XmlElementType.XML_TEXT) return false;
    final ASTNode treeParent = child.getTreeParent();
    if (treeParent == null) return false;
    if (treeParent.getElementType() != XmlElementType.XML_TAG) return false;
    final PsiElement psiElement = SourceTreeToPsiMap.treeElementToPsi(treeParent);
    final String name = ((XmlTag)psiElement).getName();
    if (!(Comparing.equal(name, JSPX_SCRIPTLET_TAG_NAME)
          || Comparing.equal(name, JSPX_DECLARATION_TAG_NAME))) {
      return false;
    }
    if (child.getText().trim().length() == 0) return false;
    return JspTextBlock.findPsiRootAt(child, myXmlFormattingPolicy.processJavaTree()) != null;
  }

  public abstract boolean insertLineBreakBeforeTag();

  public abstract boolean removeLineBreakBeforeTag();

  protected Spacing createDefaultSpace(boolean forceKeepLineBreaks, final boolean inText) {
    boolean shouldKeepLineBreaks = getShouldKeepLineBreaks(inText, forceKeepLineBreaks);
    return Spacing.createSpacing(0, Integer.MAX_VALUE, 0, shouldKeepLineBreaks, myXmlFormattingPolicy.getKeepBlankLines());
  }

  private boolean getShouldKeepLineBreaks(final boolean inText, final boolean forceKeepLineBreaks) {
    if (forceKeepLineBreaks) {
      return true;
    }
    if (inText && myXmlFormattingPolicy.getShouldKeepLineBreaksInText()) {
      return true;
    }
    if (!inText && myXmlFormattingPolicy.getShouldKeepLineBreaks()) {
      return true;
    }
    return false;
  }

  public abstract boolean isTextElement();

  private static final Logger LOG = Logger.getInstance("#com.intellij.psi.formatter.xml.AbstractXmlBlock");

  public static Block creareJspxRoot(final PsiElement element,
                                     final CodeStyleSettings settings,
                                     final FormattingDocumentModel documentModel) {
    final ASTNode rootNode = SourceTreeToPsiMap.psiElementToTree(element);
    return new XmlBlock(rootNode, null, null, new HtmlPolicy(settings, documentModel), null, null);
  }

  protected void createJspTextNode(final List<Block> localResult, final ASTNode child, final Indent indent) {

    localResult.add(new JspTextBlock(child,
                                     myXmlFormattingPolicy,
                                     JspTextBlock.findPsiRootAt(child, myXmlFormattingPolicy.processJavaTree()),
                                     indent
    ));
  }

  private static ASTNode findChildAfter(@NotNull final ASTNode child, final int endOffset) {
    TreeElement fileNode = TreeUtil.getFileElement((TreeElement)child);
    final LeafElement leaf = fileNode.findLeafElementAt(endOffset);
    if (leaf != null && leaf.getStartOffset() == endOffset && endOffset > 0) {
      return fileNode.findLeafElementAt(endOffset - 1);
    }
    return leaf;
    /*
    ASTNode result = child;
    while (result != null && result.getTextRange().getEndOffset() < endOffset) {
      result = TreeUtil.nextLeaf(result);
    }
    return result;
    */
  }

}
