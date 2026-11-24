package com.exteragram.hook;

/**
 * glassGram: what exteraGram's hook core returns for a partial hook (a hook inside a method, from
 * SDK 1.4.6). glassGram hooks with AliuHook, which has no partial hooks: a plugin asking for one
 * gets an error saying so (base_plugin), and nothing makes one of these.
 */
public interface HookHandle {
    void unhook();
}
