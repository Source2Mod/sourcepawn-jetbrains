package org.source2mod.sourcepawn

import org.junit.Assert.assertEquals
import org.junit.Test
import org.source2mod.sourcepawn.index.SpDeclarations
import org.source2mod.sourcepawn.index.SpSymbolKind as K

class SpDeclarationsTest {
    private fun scan(s: String) = SpDeclarations.scan(s).map { Triple(it.name, it.kind, it.container) }

    @Test fun declarations() {
        val code = """
            #define MAX_SLOTS 64
            enum HookPoint { HookPoint_ClientConnect, HookPoint_ClientDisconnect = 2 }
            enum struct PlayerData
            {
                int userId;
                bool IsTrusted() { return this.userId != 0; }
            }
            methodmap NetMessage < Handle
            {
                public static native NetMessage GetNestedMessage(NetMessage msg, const char[] field);
                property int Slot { public native get(); }
            }
            typedef HookCallback = function Action (int client);
            native void Source2ModLog(const char[] text);
            public void OnPluginStart()
            {
                Source2ModLog("x");
            }
        """.trimIndent()
        assertEquals(
            listOf(
                Triple("MAX_SLOTS", K.MACRO, null),
                Triple("HookPoint", K.ENUM, null),
                Triple("HookPoint_ClientConnect", K.ENUM_MEMBER, "HookPoint"),
                Triple("HookPoint_ClientDisconnect", K.ENUM_MEMBER, "HookPoint"),
                Triple("PlayerData", K.ENUM_STRUCT, null),
                Triple("IsTrusted", K.METHOD, "PlayerData"),
                Triple("NetMessage", K.METHODMAP, null),
                Triple("GetNestedMessage", K.METHOD, "NetMessage"),
                Triple("Slot", K.PROPERTY, "NetMessage"),
                Triple("HookCallback", K.TYPEDEF, null),
                Triple("Source2ModLog", K.FUNCTION, null),
                Triple("OnPluginStart", K.FUNCTION, null),
            ),
            scan(code),
        )
    }
}
