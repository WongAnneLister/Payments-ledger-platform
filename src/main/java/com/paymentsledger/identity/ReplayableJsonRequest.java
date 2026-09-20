package com.paymentsledger.identity;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Retain exact bytes after scope inspection; webhook requests never use this wrapper. */
final class ReplayableJsonRequest extends HttpServletRequestWrapper {
    private final byte[] body;
    ReplayableJsonRequest(HttpServletRequest request,byte[] body) { super(request); this.body=body; }
    @Override public ServletInputStream getInputStream() {
        ByteArrayInputStream stream=new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override public int read() { return stream.read(); }
            @Override public boolean isFinished() { return stream.available()==0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous request body only"); }
        };
    }
    @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8)); }
}
