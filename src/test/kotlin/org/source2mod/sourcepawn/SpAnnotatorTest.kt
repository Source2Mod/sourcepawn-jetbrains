package org.source2mod.sourcepawn

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.source2mod.sourcepawn.lang.SpColors
import org.source2mod.sourcepawn.lang.SpHighlightingLexer
import org.source2mod.sourcepawn.lang.SpSyntaxHighlighter
import org.source2mod.sourcepawn.lsp.SpGotoDeclarationHandler

class SpAnnotatorTest : BasePlatformTestCase() {
    private fun colors(code: String): Map<String, String> {
        myFixture.configureByText("a.sp", code)
        return myFixture.doHighlighting()
            .filter { it.forcedTextAttributesKey != null }
            .associate { code.substring(it.startOffset, it.endOffset) to it.forcedTextAttributesKey!!.externalName }
    }

    fun testIdentifiers() {
        val c = colors(
            """
            PlayerData g_Players[64];
            public void OnPluginStart()
            {
                Source2ModHooks.Hook(HookPoint_ClientConnect, OnClientConnect, true);
                int slot = client.Slot.Get();
                char name[MAX_NAME_LENGTH];
            }
            bool IsExempt(CServerSideClient client) { return !client.IsValid; }
            """.trimIndent()
        )
        assertEquals(SpColors.TYPE_NAME.externalName, c["PlayerData"])
        assertEquals(SpColors.GLOBAL_VARIABLE.externalName, c["g_Players"])
        assertEquals(SpColors.FUNCTION_DECLARATION.externalName, c["OnPluginStart"])
        assertEquals(SpColors.TYPE_NAME.externalName, c["Source2ModHooks"])
        assertEquals(SpColors.METHOD_CALL.externalName, c["Hook"])
        assertEquals(SpColors.FIELD.externalName, c["Slot"])
        assertEquals(SpColors.METHOD_CALL.externalName, c["Get"])
        assertEquals(SpColors.MACRO.externalName, c["MAX_NAME_LENGTH"])
        assertEquals(SpColors.FUNCTION_DECLARATION.externalName, c["IsExempt"])
        assertEquals(SpColors.TYPE_NAME.externalName, c["CServerSideClient"])
        assertEquals(SpColors.FIELD.externalName, c["IsValid"])
    }

    /** Quick documentation code blocks only see the highlighting lexer. */
    fun testHighlightingLexer() {
        val code = "CServerSideClient client = CServerSideClient(ctx.slot);"
        val lexer = SpHighlightingLexer()
        lexer.start(code, 0, code.length, 0)
        val keys = HashMap<String, String?>()
        val highlighter = SpSyntaxHighlighter()
        while (lexer.tokenType != null) {
            keys[code.substring(lexer.tokenStart, lexer.tokenEnd)] =
                highlighter.getTokenHighlights(lexer.tokenType).firstOrNull()?.externalName
            lexer.advance()
        }
        assertEquals(SpColors.FUNCTION_CALL.externalName, keys["CServerSideClient"]) // last one wins: the call
        assertEquals(SpColors.FIELD.externalName, keys["slot"])
        assertEquals(SpColors.IDENTIFIER.externalName, keys["client"])
    }

    fun testIncludeNavigation() {
        val inc = myFixture.addFileToProject("scripting/include/source2mod/core.inc", "native void Foo();")
        myFixture.addFileToProject("scripting/b.sp", "#include <source2mod/core>\n")
        myFixture.configureFromTempProjectFile("scripting/b.sp")
        myFixture.editor.caretModel.moveToOffset("#include <source2mod/co".length)
        val sp = myFixture.file
        val element = sp.findElementAt(myFixture.caretOffset)
        val targets = SpGotoDeclarationHandler().getGotoDeclarationTargets(element, myFixture.caretOffset, myFixture.editor)
        assertEquals(inc.virtualFile, targets?.singleOrNull()?.containingFile?.virtualFile)
    }
}
