package com.limelight.nvstream.http;

import java.io.IOException;

public class HostHttpResponseException extends IOException {
    private static final long serialVersionUID = 1543508830807804222L;
    
    private int errorCode;
    private String errorMsg;
    private String sideLanternReason;

    public HostHttpResponseException(int errorCode, String errorMsg) {
        this(errorCode, errorMsg, null);
    }

    public HostHttpResponseException(int errorCode, String errorMsg, String sideLanternReason) {
        this.errorCode = errorCode;
        this.errorMsg = errorMsg;
        this.sideLanternReason = sideLanternReason;
    }

    public int getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMsg;
    }

    public String getSideLanternReason() {
        return sideLanternReason;
    }
    
    @Override
    public String getMessage() {
        return "Host PC returned error: "+errorMsg+" (Error code: "+errorCode+")";
    }
}
