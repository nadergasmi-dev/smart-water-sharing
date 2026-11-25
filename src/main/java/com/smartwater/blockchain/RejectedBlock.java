package com.smartwater.blockchain;

// Un bloc refusé par la validation
public record RejectedBlock(Block block, String reason, long time) { }
