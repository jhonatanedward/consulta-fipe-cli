package br.com.edward.consulta_fipe_cli.utils;

import java.util.Map;

public interface IHttpClient {
    <T> T get(String uri, Map<String, String> headers, Class<T> responseType);
    <T> T post(String uri, Map<String, String> headers, Object requestBody, Class<T> responseType);
    <T> T put(String uri, Map<String, String> headers, Object requestBody, Class<T> responseType);
    void delete(String uri, Map<String, String> headers);
}
