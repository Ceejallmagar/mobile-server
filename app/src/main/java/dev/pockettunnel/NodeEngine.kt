package dev.pockettunnel

internal object NodeEngine {
    init { System.loadLibrary("pocketnode") }
    external fun redirectOutput(path: String): Int
    external fun startNode(arguments: Array<String>, workingDirectory: String, port: String, appHome: String, tempDirectory: String): Int
}
