package org.telegram.messenger;

// Older plugin DEX resolves the field by name and type, including inherited fields.
// (In a file of its own so that Kotlin code finds it as Utilities' superclass.)
class UtilitiesQueueCompat {
    static final DispatchQueue2 sharedGlobalQueue = new DispatchQueue2("globalQueue");
    public static volatile DispatchQueue globalQueue = sharedGlobalQueue;
}
