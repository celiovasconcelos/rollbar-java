package com.rollbar.notifier.sender;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rollbar.api.payload.Payload;
import com.rollbar.notifier.sender.exception.ApiException;
import com.rollbar.notifier.sender.exception.SenderException;
import com.rollbar.notifier.sender.json.JsonSerializer;
import com.rollbar.notifier.sender.listener.SenderListener;
import com.rollbar.notifier.sender.result.Response;
import com.rollbar.notifier.sender.result.Result;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

public class SyncSenderTest {

  static final String UTF_8 = "UTF-8";
  
  static final String PAYLOAD_JSON = "simulated_payload_json";

  @Rule
  public MockitoRule rule = MockitoJUnit.rule();

  @Mock
  URL url;

  @Mock
  HttpURLConnection connection;

  @Mock
  OutputStream out;

  @Mock
  InputStream input;

  @Mock
  JsonSerializer serializer;

  @Mock
  SenderListener listener;

  @Mock
  Payload payload;

  @Mock
  Result result;

  SyncSender sut;

  @Before
  public void setUp()throws Exception {
    when(url.openConnection()).thenReturn(connection);
    when(connection.getOutputStream()).thenReturn(out);

    when(serializer.toJson(payload)).thenReturn(PAYLOAD_JSON);

    sut = new SyncSender.Builder()
        .url(url)
        .jsonSerializer(serializer)
        .build();
    sut.addListener(listener);
  }

  @Test
  public void shouldSendThePayloadWithSuccessResult() throws Exception {
    int responseCode = 200;
    String responseJson = "simulated_response_json";

    when(connection.getResponseCode()).thenReturn(responseCode);
    when(connection.getInputStream())
        .thenReturn(new ByteArrayInputStream(responseJson.getBytes(UTF_8)));

    when(serializer.resultFrom(responseJson)).thenReturn(result);

    sut.send(payload);

    Response expectedResponse = new Response.Builder()
        .status(responseCode)
        .result(result)
        .build();

    verifyHttp();
    verify(connection).getInputStream();
    verify(listener).onResponse(payload, expectedResponse);
  }

  @Test
  public void shouldSendThePayloadWithResponseError() throws Exception {
    int responseCode = 400;
    String responseJson = "simulated_response_json";
    String apiMessage = "simulated_api_error_json";
    Result result = new Result.Builder()
        .code(1)
        .body(apiMessage)
        .build();
    Response response = new Response.Builder()
        .status(responseCode)
        .result(result)
        .build();

    ApiException sourceError = new ApiException(response);

    when(connection.getResponseCode()).thenReturn(responseCode);
    when(connection.getErrorStream())
        .thenReturn(new ByteArrayInputStream(responseJson.getBytes(UTF_8)));

    when(serializer.resultFrom(responseJson)).thenReturn(result);

    sut.send(payload);

    verifyHttp();
    verify(connection).getErrorStream();
    ArgumentCaptor<SenderException> argument = ArgumentCaptor.forClass(SenderException.class);
    verify(listener).onError(eq(payload), argument.capture());

    assertThat(argument.getValue(), is(instanceOf(SenderException.class)));
    assertThat(argument.getValue().getCause(), is(sourceError));
  }

  @Test
  public void shouldNotifyErrorDuringSend() throws Exception {
    IOException sourceError = new IOException("Error opening the connection.");

    byte[] bytes = PAYLOAD_JSON.getBytes(UTF_8);

    doThrow(sourceError).when(out).write(bytes, 0, bytes.length);

    sut.send(payload);

    verifyHttp();
    ArgumentCaptor<SenderException> argument = ArgumentCaptor.forClass(SenderException.class);
    verify(listener).onError(eq(payload), argument.capture());

    assertThat(argument.getValue(), is(instanceOf(SenderException.class)));
    assertThat(argument.getValue().getCause(), is(sourceError));
  }

  @Test
  public void shouldClose() throws Exception {
    sut.close();

    verify(connection).disconnect();
  }

  private void verifyHttp() throws Exception {
    verify(connection).setRequestProperty("Accept-Charset", UTF_8);
    verify(connection).setRequestProperty("Content-Type", "application/json; charset=" + UTF_8);
    verify(connection).setRequestProperty("Accept", "application/json");
    verify(connection).setDoOutput(true);
    verify(connection).setRequestMethod("POST");
  }
}