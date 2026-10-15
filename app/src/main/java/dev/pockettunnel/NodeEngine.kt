package dev.pockettunnel

internal object NodeEngine {
    init { System.loadLibrary("pocketnode") }
    external fun startNode(arguments: Array<String>, workingDirectory: String, port: String): Int
}
