package dev.pockettunnel

/** Each subclass runs in its own isolated Android process so several tunnels can run concurrently. */
class RuntimeSlot1 : RuntimeService()
class RuntimeSlot2 : RuntimeService()
class RuntimeSlot3 : RuntimeService()
