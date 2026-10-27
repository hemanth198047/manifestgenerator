package com.example.casc.web;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.Map;

/** The small part of WebServer that the wizard controller needs. */
interface WebSupport {
    String readBody(HttpExchange x) throws Exception;

    void page(HttpExchange x, int status, String title, String template, Map<String, String> values) throws IOException;

    void send(HttpExchange x, int status, String type, byte[] body) throws IOException;

    void text(HttpExchange x, int status, String body) throws IOException;
}
