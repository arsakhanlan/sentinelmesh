package com.sentinelmesh.common.error;

public class SentinelMeshException extends RuntimeException {
    public SentinelMeshException(String msg) { super(msg); }
    public SentinelMeshException(String msg, Throwable cause) { super(msg, cause); }
}
