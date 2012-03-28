package ro.isdc.wro.http.support;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.servlet.http.HttpServletResponse;

import junit.framework.Assert;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import ro.isdc.wro.model.resource.locator.ResourceLocator;
import ro.isdc.wro.model.resource.locator.support.ClasspathResourceLocator;

/**
 * @author Alex Objelean
 */
public class TestRedirectedStreamServletResponseWrapper {
  private RedirectedStreamServletResponseWrapper victim;
  @Mock
  private HttpServletResponse mockResponse;
  private ByteArrayOutputStream redirectedStream;
  @Before
  public void setUp() {
    redirectedStream = new ByteArrayOutputStream();
    MockitoAnnotations.initMocks(this);
    victim = new RedirectedStreamServletResponseWrapper(redirectedStream, mockResponse);
  }

  @Test(expected = IllegalArgumentException.class)
  public void cannotAcceptNullResponse() {
    new RedirectedStreamServletResponseWrapper(new ByteArrayOutputStream(), null);
  }

  @Test(expected = NullPointerException.class)
  public void cannotAcceptNullStream() {
    new RedirectedStreamServletResponseWrapper(null, mockResponse);
  }

  @Test
  public void shouldRedirectWriter() throws Exception {
    final String message = "Hello world!";
    victim.getWriter().write(message);
    victim.getWriter().flush();
    Assert.assertEquals(message, new String(redirectedStream.toByteArray()));
  }

  @Test
  public void shouldRedirectStream() throws Exception {
    final String message = "Hello world!";
    victim.getOutputStream().write(message.getBytes());
    victim.getOutputStream().flush();
    Assert.assertEquals(message, new String(redirectedStream.toByteArray()));
  }

  /**
   * instruct vitim to use custom external resource locator (to return expected message).
   */
  @Test
  public void shouldRedirectStreamWhenSendRedirectIsInvoked() throws Exception {
    final String message = "Hello world!";
    victim = new RedirectedStreamServletResponseWrapper(redirectedStream, mockResponse) {
      @Override
      protected ResourceLocator newExternalResourceLocator(final String location) {
        return new ClasspathResourceLocator(location) {
          @Override
          public InputStream getInputStream()
              throws IOException {
            return new ByteArrayInputStream(message.getBytes());
          }
        };
      }
    };
    victim.sendRedirect("/does/not/matter");
    Assert.assertEquals(message, new String(redirectedStream.toByteArray()));
  }
}