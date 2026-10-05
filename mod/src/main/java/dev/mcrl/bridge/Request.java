package dev.mcrl.bridge;

/** A validated request from the Python agent. Unused fields are 0. */
public record Request(String cmd, int action, long seed, int stage) {}
