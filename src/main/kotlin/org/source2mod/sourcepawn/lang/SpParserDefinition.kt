package org.source2mod.sourcepawn.lang

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.psi.tree.TokenSet
import org.source2mod.sourcepawn.SpFileType
import org.source2mod.sourcepawn.SpIncludeFileType
import org.source2mod.sourcepawn.SpLanguage

class SpFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, SpLanguage) {
    override fun getFileType(): FileType =
        if (viewProvider.virtualFile.extension.equals("inc", true)) SpIncludeFileType else SpFileType

    override fun toString() = "SourcePawn file"
}

/**
 * The PSI is flat (a file of tokens): structure, references and types all come
 * from the language server, so there is no grammar to keep in sync with spcomp.
 */
class SpParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?) = SpLexer()

    override fun createParser(project: Project?) = PsiParser { root, builder ->
        val marker = builder.mark()
        while (!builder.eof()) builder.advanceLexer()
        marker.done(root)
        builder.treeBuilt
    }

    override fun getFileNodeType() = SpTokenTypes.FILE
    override fun getWhitespaceTokens(): TokenSet = SpTokenTypes.WHITESPACES
    override fun getCommentTokens(): TokenSet = SpTokenTypes.COMMENTS
    override fun getStringLiteralElements(): TokenSet = SpTokenTypes.STRINGS
    override fun createElement(node: ASTNode): PsiElement = ASTWrapperPsiElement(node)
    override fun createFile(viewProvider: FileViewProvider) = SpFile(viewProvider)
}
