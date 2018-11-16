/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 1997-2010 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.

 This program is distributed in the hope that it will be useful, but WITHOUT
 ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License along
 with this program; if not, see http://www.gnu.org/licenses or write to the Free
 Software Foundation,Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301
 */
package com.servoy.extensions.plugins.rest_ws.servlets;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.mail.BodyPart;
import javax.mail.MessagingException;
import javax.mail.internet.MimeMultipart;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.json.JSONException;
import org.json.XML;
import org.mozilla.javascript.JavaScriptException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.Wrapper;
import org.mozilla.javascript.xml.XMLObject;

import com.servoy.extensions.plugins.rest_ws.RestWSClientPlugin;
import com.servoy.extensions.plugins.rest_ws.RestWSPlugin;
import com.servoy.extensions.plugins.rest_ws.RestWSPlugin.ExecFailedException;
import com.servoy.extensions.plugins.rest_ws.RestWSPlugin.NoClientsException;
import com.servoy.extensions.plugins.rest_ws.RestWSPlugin.NotAuthenticatedException;
import com.servoy.extensions.plugins.rest_ws.RestWSPlugin.NotAuthorizedException;
import com.servoy.j2db.plugins.IClientPlugin;
import com.servoy.j2db.plugins.IClientPluginAccess;
import com.servoy.j2db.scripting.FunctionDefinition;
import com.servoy.j2db.scripting.FunctionDefinition.Exist;
import com.servoy.j2db.scripting.JSMap;
import com.servoy.j2db.server.shared.IHeadlessClient;
import com.servoy.j2db.util.Debug;
import com.servoy.j2db.util.HTTPUtils;
import com.servoy.j2db.util.Pair;
import com.servoy.j2db.util.Utils;

/**
 * Servlet for mapping RESTfull Web Service request to Servoy methods.
 * <p>
 * Resources are addressed via path
 *
 * <pre>
 * /servoy-service/rest_ws/mysolution/myform/arg1/arg2
 * </pre>.
 * <p>
 * HTTP methods are
 * <ul>
 * <li>POST<br>
 * call the method mysolution.myform.ws_create(post-data), return the method result in the response
 * <li>GET<br>
 * call the method mysolution.myform.ws_read(args), return the method result in the response or set status NOT_FOUND when null was returned
 * <li>UPDATE<br>
 * call the method mysolution.myform.ws_update(post-data, args), set status NOT_FOUND when FALSE was returned
 * <li>PATCH<br>
 * call the method mysolution.myform.ws_patch(patch-data, args), set status NOT_FOUND when FALSE was returned
 * <li>DELETE<br>
 * call the method mysolution.myform.ws_delete(args), set status NOT_FOUND when FALSE was returned
 * </ul>
 *
 * <p>
 * The solution is opened via a Servoy Headless Client which is shared across multiple requests, requests are assumed to be stateless. Clients are managed via a
 * pool, 1 client per concurrent request is used.
 *
 * @author rgansevles
 *
 */
@SuppressWarnings("nls")
public class RestWSServlet extends HttpServlet
{
	// solution method names
	private static final String WS_UPDATE = "ws_update";
	private static final String WS_PATCH = "ws_patch";
	private static final String WS_CREATE = "ws_create";
	private static final String WS_DELETE = "ws_delete";
	private static final String WS_READ = "ws_read";
	private static final String WS_AUTHENTICATE = "ws_authenticate";
	private static final String WS_RESPONSE_HEADERS = "ws_response_headers";
	private static final String WS_NODEBUG_HEADER = "servoy.nodebug";
	private static final String WS_USER_PROPERTIES_HEADER = "servoy.userproperties";
	private static final String WS_USER_PROPERTIES_COOKIE_PREFIX = "servoy.userproperty.";

	private static final int CONTENT_OTHER = 0;
	private static final int CONTENT_JSON = 1;
	private static final int CONTENT_XML = 2;
	private static final int CONTENT_BINARY = 3;
	private static final int CONTENT_MULTIPART = 4;
	private static final int CONTENT_TEXT = 5;
	private static final int CONTENT_FORMPOST = 6;

	private static final int CONTENT_DEFAULT = CONTENT_JSON;
	private static final String CHARSET_DEFAULT = "UTF-8";

	/**
	 * Just a convention used by Servoy in ws_response_headers() return value to define the name/key of a header to be returned. (must be String)
	 */
	private static final String HEADER_NAME = "name";
	/**
	 * Just a convention used by Servoy in ws_response_headers() return value to define the value of a header to be returned. (must be String)
	 */
	private static final String HEADER_VALUE = "value";

	private final RestWSPlugin plugin;

	private final String webServiceName;

	public RestWSServlet(String webServiceName, RestWSPlugin restWSPlugin)
	{
		this.webServiceName = webServiceName;
		this.plugin = restWSPlugin;
	}

	@Override
	protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
	{
		RestWSServletResponse restWSServletResponse = new RestWSServletResponse(response);

		String value = request.getHeader("Origin");
		if (value == null)
		{
			value = "*";
		}
		restWSServletResponse.setHeader("Access-Control-Allow-Origin", value);
		restWSServletResponse.setHeader("Access-Control-Max-Age", "1728000");
		restWSServletResponse.setHeader("Access-Control-Allow-Credentials", "true");

		if (request.getHeader("Access-Control-Request-Method") != null)
		{
			restWSServletResponse.setHeader("Access-Control-Allow-Methods", "GET, DELETE, POST, PUT, OPTIONS");
		}

		if (getNodebugHeadderValue(request))
		{
			restWSServletResponse.setHeader("Access-Control-Expose-Headers", WS_NODEBUG_HEADER + ", " + WS_USER_PROPERTIES_HEADER);
		}
		else
		{
			restWSServletResponse.setHeader("Access-Control-Expose-Headers", WS_USER_PROPERTIES_HEADER);
		}
		value = request.getHeader("Access-Control-Request-Headers");
		if (value != null)
		{
			restWSServletResponse.setHeader("Access-Control-Allow-Headers", value);
		}

		if (request.getMethod().equals("PATCH"))
		{
			doPatch(request, restWSServletResponse);
		}
		else
		{
			super.service(request, restWSServletResponse);
		}
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
	{
		Pair<IHeadlessClient, String> client = null;
		boolean reloadSolution = plugin.shouldReloadSolutionAfterRequest();
		try
		{
			plugin.log.trace("GET");
			client = getClient(request);
			Object result = wsService(WS_READ, null, request, response, client.getLeft());
			if (result == null)
			{
				sendError(response, HttpServletResponse.SC_NOT_FOUND);
				return;
			}
			HTTPUtils.setNoCacheHeaders(response);
			sendResult(request, response, result, CONTENT_DEFAULT);
		}
		catch (ExecFailedException e)
		{
			handleException(e.getCause(), request, response, client != null ? client.getLeft() : null);
			// do not reload solution when the error was thrown in solution code
			if (!reloadSolution) reloadSolution = !e.isUserScriptException();
		}
		catch (Exception e)
		{
			handleException(e, request, response, client != null ? client.getLeft() : null);
		}
		finally
		{
			if (client != null)
			{
				plugin.releaseClient(client.getRight(), client.getLeft(), reloadSolution);
			}
		}
	}

	/**
	 *
	 * @param request HttpServletRequest
	 * @return  a pair of IHeadlessClient object and the keyname from the objectpool ( the keyname depends if it nodebug mode is enabled)
	 * @throws Exception
	 */
	private Pair<IHeadlessClient, String> getClient(HttpServletRequest request) throws Exception
	{
		WsRequestPath wsRequestPath = parsePath(request);
		boolean nodebug = getNodebugHeadderValue(request);
		String solutionName = nodebug ? wsRequestPath.solutionName + ":nodebug" : wsRequestPath.solutionName;
		IHeadlessClient client = plugin.getClient(solutionName.toString());
		return new Pair<IHeadlessClient, String>(client, solutionName);
	}

	private void handleException(Exception e, HttpServletRequest request, HttpServletResponse response, IHeadlessClient headlessClient) throws IOException
	{
		final int errorCode;
		String errorResponse = null;
		if (e instanceof NotAuthenticatedException)
		{
			if (plugin.log.isDebugEnabled()) plugin.log.debug(request.getRequestURI() + ": Not authenticated");
			response.setHeader("WWW-Authenticate", "Basic realm=\"" + ((NotAuthenticatedException)e).getRealm() + '"');
			errorCode = HttpServletResponse.SC_UNAUTHORIZED;
		}
		else if (e instanceof NotAuthorizedException)
		{
			plugin.log.info(request.getRequestURI() + ": Not authorised: " + e.getMessage());
			errorCode = HttpServletResponse.SC_FORBIDDEN;
		}
		else if (e instanceof NoClientsException)
		{
			plugin.log.error(
				"Client could not be found. Possible reasons: 1.Client could not be created due to maximum number of licenses reached. 2.Client could not be created due to property mustAuthenticate=true in ws solution. 3.The client pool reached maximum number of clients. 4.An internal error occured. " +
					request.getRequestURI(),
				e);
			errorCode = HttpServletResponse.SC_SERVICE_UNAVAILABLE;
		}
		else if (e instanceof IllegalArgumentException)
		{
			plugin.log.info("Could not parse path '" + e.getMessage() + '\'');
			errorCode = HttpServletResponse.SC_BAD_REQUEST;
		}
		else if (e instanceof WebServiceException)
		{
			plugin.log.info(request.getRequestURI(), e);
			errorCode = ((WebServiceException)e).httpResponseCode;
		}
		else if (e instanceof JavaScriptException)
		{
			plugin.log.info("ws_ method threw an exception '" + e.getMessage() + '\'');
			if (((JavaScriptException)e).getValue() instanceof Double)
			{
				errorCode = ((Double)((JavaScriptException)e).getValue()).intValue();

			}
			else if (((JavaScriptException)e).getValue() instanceof Wrapper && ((Wrapper)((JavaScriptException)e).getValue()).unwrap() instanceof Object[])
			{
				Object[] throwval = (Object[])((Wrapper)((JavaScriptException)e).getValue()).unwrap();
				errorCode = Utils.getAsInteger(throwval[0]);
				errorResponse = throwval[1] != null ? throwval[1].toString() : null;
			}
			else
			{
				if (headlessClient != null) headlessClient.getPluginAccess().reportError("Error executing rest call", e);
				errorCode = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
			}
		}
		else
		{
			plugin.log.error(request.getRequestURI(), e);
			if (headlessClient != null) headlessClient.getPluginAccess().reportError("Error executing rest call", e);
			errorCode = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
		}
		sendError(response, errorCode, errorResponse);
	}

	@Override
	protected void doDelete(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
	{
		Pair<IHeadlessClient, String> client = null;
		boolean reloadSolution = plugin.shouldReloadSolutionAfterRequest();
		try
		{
			plugin.log.trace("DELETE");

			client = getClient(request);
			if (Boolean.FALSE.equals(wsService(WS_DELETE, null, request, response, client.getLeft())))
			{
				sendError(response, HttpServletResponse.SC_NOT_FOUND);
			}
			HTTPUtils.setNoCacheHeaders(response);
		}
		catch (ExecFailedException e)
		{
			handleException(e.getCause(), request, response, client != null ? client.getLeft() : null);
			// do not reload solution when the error was thrown in solution code
			if (!reloadSolution) reloadSolution = !e.isUserScriptException();
		}
		catch (Exception e)
		{
			handleException(e, request, response, client != null ? client.getLeft() : null);
		}
		finally
		{
			if (client != null)
			{
				plugin.releaseClient(client.getRight(), client.getLeft(), reloadSolution);
			}
		}
	}

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
	{
		Pair<IHeadlessClient, String> client = null;
		boolean reloadSolution = plugin.shouldReloadSolutionAfterRequest();
		try
		{
			byte[] contents = getBody(request);
			if (contents == null || contents.length == 0)
			{
				sendError(response, HttpServletResponse.SC_NO_CONTENT);
				return;
			}
			int contentType = getRequestContentType(request, "Content-Type", contents, CONTENT_OTHER);
			if (contentType == CONTENT_OTHER)
			{
				sendError(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
				return;
			}
			client = getClient(request);
			String charset = getHeaderKey(request.getHeader("Content-Type"), "charset", CHARSET_DEFAULT);
			Object result = wsService(WS_CREATE, new Object[] { decodeContent(request.getContentType(), contentType, contents, charset) }, request, response,
				client.getLeft());
			HTTPUtils.setNoCacheHeaders(response);
			if (result != null && result != Undefined.instance)
			{
				sendResult(request, response, result, contentType);
			}
		}
		catch (ExecFailedException e)
		{
			handleException(e.getCause(), request, response, client != null ? client.getLeft() : null);
			// do not reload solution when the error was thrown in solution code
			if (!reloadSolution) reloadSolution = !e.isUserScriptException();
		}
		catch (Exception e)
		{
			handleException(e, request, response, client != null ? client.getLeft() : null);
		}
		finally
		{
			if (client != null)
			{
				plugin.releaseClient(client.getRight(), client.getLeft(), reloadSolution);
			}
		}
	}

	@Override
	protected void doPut(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
	{
		Pair<IHeadlessClient, String> client = null;
		boolean reloadSolution = plugin.shouldReloadSolutionAfterRequest();
		try
		{
			byte[] contents = getBody(request);
			if (contents == null || contents.length == 0)
			{
				sendError(response, HttpServletResponse.SC_NO_CONTENT);
				return;
			}
			int contentType = getRequestContentType(request, "Content-Type", contents, CONTENT_OTHER);
			if (contentType == CONTENT_OTHER)
			{
				sendError(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
				return;
			}
			client = getClient(request);
			String charset = getHeaderKey(request.getHeader("Content-Type"), "charset", CHARSET_DEFAULT);
			Object result = wsService(WS_UPDATE, new Object[] { decodeContent(request.getContentType(), contentType, contents, charset) }, request, response,
				client.getLeft());
			if (Boolean.FALSE.equals(result))
			{
				sendError(response, HttpServletResponse.SC_NOT_FOUND);
			}
			else
			{
				sendResult(request, response, result, contentType);
			}
			HTTPUtils.setNoCacheHeaders(response);
		}
		catch (ExecFailedException e)
		{
			handleException(e.getCause(), request, response, client != null ? client.getLeft() : null);
			// do not reload solution when the error was thrown in solution code
			if (!reloadSolution) reloadSolution = !e.isUserScriptException();
		}
		catch (Exception e)
		{
			handleException(e, request, response, client != null ? client.getLeft() : null);
		}
		finally
		{
			if (client != null)
			{
				plugin.releaseClient(client.getRight(), client.getLeft(), reloadSolution);
			}
		}
	}

	private void doPatch(HttpServletRequest request, HttpServletResponse response) throws IOException
	{
		Pair<IHeadlessClient, String> client = null;
		boolean reloadSolution = plugin.shouldReloadSolutionAfterRequest();
		try
		{
			byte[] contents = getBody(request);
			if (contents == null || contents.length == 0)
			{
				sendError(response, HttpServletResponse.SC_NO_CONTENT);
				return;
			}
			int contentType = getRequestContentType(request, "Content-Type", contents, CONTENT_OTHER);
			if (contentType == CONTENT_OTHER)
			{
				sendError(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
				return;
			}
			client = getClient(request);
			String charset = getHeaderKey(request.getHeader("Content-Type"), "charset", CHARSET_DEFAULT);
			Object result = wsService(WS_PATCH, new Object[] { decodeContent(request.getContentType(), contentType, contents, charset) }, request, response,
				client.getLeft());
			if (Boolean.FALSE.equals(result))
			{
				sendError(response, HttpServletResponse.SC_NOT_FOUND);
			}
			else
			{
				sendResult(request, response, result, contentType);
			}
			HTTPUtils.setNoCacheHeaders(response);
		}
		catch (ExecFailedException e)
		{
			handleException(e.getCause(), request, response, client != null ? client.getLeft() : null);
			// do not reload solution when the error was thrown in solution code
			if (!reloadSolution) reloadSolution = !e.isUserScriptException();
		}
		catch (Exception e)
		{
			handleException(e, request, response, client != null ? client.getLeft() : null);
		}
		finally
		{
			if (client != null)
			{
				plugin.releaseClient(client.getRight(), client.getLeft(), reloadSolution);
			}
		}
	}

	@Override
	protected void doOptions(HttpServletRequest request, HttpServletResponse response) throws IOException
	{
		IHeadlessClient client = null;
		WsRequestPath wsRequestPath = null;
		boolean nodebug = getNodebugHeadderValue(request);
		boolean reloadSolution = plugin.shouldReloadSolutionAfterRequest();
		try
		{
			plugin.log.trace("OPTIONS");
			wsRequestPath = parsePath(request);

			client = plugin.getClient(nodebug ? wsRequestPath.solutionName + ":nodebug" : wsRequestPath.solutionName);
			setApplicationUserProperties(request, client.getPluginAccess());
			String retval = "TRACE, OPTIONS";
			if (new FunctionDefinition(wsRequestPath.formName, WS_READ).exists(client.getPluginAccess()) == FunctionDefinition.Exist.METHOD_FOUND)
			{
				retval += ", GET";
			}
			//TODO: implement HEAD?
			retval += ", HEAD";
			if (new FunctionDefinition(wsRequestPath.formName, WS_CREATE).exists(client.getPluginAccess()) == FunctionDefinition.Exist.METHOD_FOUND)
			{
				retval += ", POST";
			}
			if (new FunctionDefinition(wsRequestPath.formName, WS_UPDATE).exists(client.getPluginAccess()) == FunctionDefinition.Exist.METHOD_FOUND)
			{
				retval += ", PUT";
			}
			if (new FunctionDefinition(wsRequestPath.formName, WS_PATCH).exists(client.getPluginAccess()) == FunctionDefinition.Exist.METHOD_FOUND)
			{
				retval += ", PATCH";
			}
			if (new FunctionDefinition(wsRequestPath.formName, WS_DELETE).exists(client.getPluginAccess()) == FunctionDefinition.Exist.METHOD_FOUND)
			{
				retval += ", DELETE";
			}

			response.setHeader("Allow", retval);

			String value = request.getHeader("Access-Control-Request-Headers");
			if (value == null)
			{
				value = "Allow";
			}
			else if (!value.contains("Allow"))
			{
				value += ", Allow";
			}
			response.setHeader("Access-Control-Allow-Headers", value);
			response.setHeader("Access-Control-Expose-Headers", value + ", " + WS_NODEBUG_HEADER + ", " + WS_USER_PROPERTIES_HEADER);
			setResponseUserProperties(request, response, client.getPluginAccess());
		}
		catch (ExecFailedException e)
		{
			handleException(e.getCause(), request, response, client);
			// do not reload solution when the error was thrown in solution code
			if (!reloadSolution) reloadSolution = !e.isUserScriptException();
		}
		catch (Exception e)
		{
			handleException(e, request, response, client);
		}
		finally
		{
			if (client != null)
			{
				plugin.releaseClient(nodebug ? wsRequestPath.solutionName + ":nodebug" : wsRequestPath.solutionName, client, reloadSolution);
			}
		}
	}

	public WsRequestPath parsePath(HttpServletRequest request)
	{
		String path = request.getPathInfo(); //without servlet name

		if (plugin.log.isDebugEnabled()) plugin.log.debug("Request '" + path + '\'');

		// parse the path: /webServiceName/mysolution/myform/arg1/arg2/...
		String[] segments = path == null ? null : path.split("/");
		if (segments == null || segments.length < 4 || !webServiceName.equals(segments[1]))
		{
			throw new IllegalArgumentException(path);
		}

		return new WsRequestPath(segments[2], segments[3], Utils.arraySub(segments, 4, segments.length));
	}

	/**
	 * call the service method, make the request ansd response available for the client-plugin.
	 * Throws {@link NoClientsException} when no license is available
	 * @param methodName
	 * @param fixedArgs
	 * @param request
	 * @param response
	 * @return
	 * @throws Exception
	 */
	private Object wsService(String methodName, Object[] fixedArgs, HttpServletRequest request, HttpServletResponse response, IHeadlessClient client)
		throws Exception
	{
		RestWSClientPlugin clientPlugin = (RestWSClientPlugin)client.getPluginAccess().getPluginManager().getPlugin(IClientPlugin.class,
			RestWSClientPlugin.PLUGIN_NAME);
		try
		{
			if (clientPlugin == null)
			{
				plugin.log.warn("Could not find client plugin " + RestWSClientPlugin.PLUGIN_NAME);
			}
			else
			{
				clientPlugin.setRequestResponse(request, response);
			}
			return doWsService(methodName, fixedArgs, request, response, client);
		}
		finally
		{
			if (clientPlugin != null)
			{
				clientPlugin.setRequestResponse(null, null);
			}
		}
	}

	private Object doWsService(String methodName, Object[] fixedArgs, HttpServletRequest request, HttpServletResponse response, IHeadlessClient client)
		throws Exception
	{
		// update cookies in the application from request
		setApplicationUserProperties(request, client.getPluginAccess());
		String path = request.getPathInfo(); // without servlet name

		if (plugin.log.isDebugEnabled()) plugin.log.debug("Request '" + path + '\'');

		WsRequestPath wsRequestPath = parsePath(request);

		Object ws_authenticate_result = checkAuthorization(request, client.getPluginAccess(), wsRequestPath.solutionName, wsRequestPath.formName);

		FunctionDefinition fd = new FunctionDefinition(wsRequestPath.formName, methodName);
		Exist functionExists = fd.exists(client.getPluginAccess());
		if (functionExists == FunctionDefinition.Exist.NO_SOLUTION)
		{
			throw new WebServiceException("Solution " + wsRequestPath.solutionName + " not loaded", HttpServletResponse.SC_SERVICE_UNAVAILABLE);
		}
		if (functionExists == FunctionDefinition.Exist.FORM_NOT_FOUND)
		{
			throw new WebServiceException("Form " + wsRequestPath.formName + " not found", HttpServletResponse.SC_NOT_FOUND);
		}
		if (functionExists != FunctionDefinition.Exist.METHOD_FOUND)
		{
			throw new WebServiceException("Method " + methodName + " not found" + (wsRequestPath.formName != null ? " on form " + wsRequestPath.formName : ""),
				HttpServletResponse.SC_METHOD_NOT_ALLOWED);
		}

		FunctionDefinition fd_headers = new FunctionDefinition(wsRequestPath.formName, WS_RESPONSE_HEADERS);
		if (fd_headers.exists(client.getPluginAccess()) == FunctionDefinition.Exist.METHOD_FOUND)
		{
			Object result = fd_headers.executeSync(client.getPluginAccess(), null);

			if (result instanceof Object[])
			{
				Object[] resultArray = (Object[])result;
				for (Object element : resultArray)
				{
					addHeaderToResponse(response, element, wsRequestPath);
				}
			}
			else addHeaderToResponse(response, result, wsRequestPath);
		}

		Object[] args = null;
		if (fixedArgs != null || wsRequestPath.args.length > 0 || request.getParameterMap().size() > 0)
		{
			args = new Object[((fixedArgs == null) ? 0 : fixedArgs.length) + wsRequestPath.args.length +
				((request.getParameterMap().size() > 0 || ws_authenticate_result != null) ? 1 : 0)];
			int idx = 0;
			if (fixedArgs != null)
			{
				System.arraycopy(fixedArgs, 0, args, 0, fixedArgs.length);
				idx += fixedArgs.length;
			}
			if (wsRequestPath.args.length > 0)
			{
				System.arraycopy(wsRequestPath.args, 0, args, idx, wsRequestPath.args.length);
				idx += wsRequestPath.args.length;
			}
			if (request.getParameterMap().size() > 0 || ws_authenticate_result != null)
			{
				JSMap<String, Object> jsMap = new JSMap<String, Object>();
				jsMap.putAll(request.getParameterMap());
				if (ws_authenticate_result != null)
				{
					jsMap.put(WS_AUTHENTICATE, new Object[] { ws_authenticate_result });
				}
				args[idx++] = jsMap;
			}
		}

		if (plugin.log.isDebugEnabled()) plugin.log.debug("executeMethod('" + wsRequestPath.formName + "', '" + methodName + "', <args>)");
		// DO NOT USE FunctionDefinition here! we want to be able to catch possible exceptions!
		Object result;
		try
		{
			result = client.getPluginAccess().executeMethod(wsRequestPath.formName, methodName, args, false);
		}
		catch (Exception e)
		{
			plugin.log.info("Method execution failed: executeMethod('" + wsRequestPath.formName + "', '" + methodName + "', <args>)", e);
			throw new ExecFailedException(e);
		}
		if (plugin.log.isDebugEnabled()) plugin.log.debug("result = " + (result == null ? "<NULL>" : ("'" + result + '\'')));
		// flush updated cookies from the application
		setResponseUserProperties(request, response, client.getPluginAccess());
		return result;
	}

	private void addHeaderToResponse(HttpServletResponse response, Object headerItem, WsRequestPath wsRequestPath)
	{
		boolean done = false;
		if (headerItem instanceof String)
		{
			// something like 'Content-Disposition=attachment;filename="test.txt"'
			String headerString = (String)headerItem;
			int equalSignIndex = headerString.indexOf('=');
			if (equalSignIndex > 0)
			{
				response.addHeader(headerString.substring(0, equalSignIndex), headerString.substring(equalSignIndex + 1));
				done = true;
			}
		}
		else if (headerItem instanceof Scriptable)
		{
			// something like {
			// 			name: "Content-Disposition",
			// 			value: 'attachment;filename="test.txt"'
			// }
			Scriptable headerItemObject = (Scriptable)headerItem;
			if (headerItemObject.has(HEADER_NAME, headerItemObject) && headerItemObject.has(HEADER_VALUE, headerItemObject))
			{
				response.addHeader(String.valueOf(headerItemObject.get(HEADER_NAME, headerItemObject)),
					String.valueOf(headerItemObject.get(HEADER_VALUE, headerItemObject)));
				done = true;
			}
		}

		if (!done) Debug.error(
			"Cannot send back header value from 'ws_response_headers'; it should be either a String containing a key-value pair separated by an equal sign or an object with 'name' and 'value' in it, but it is: '" +
				headerItem + "'. Solution/form: " + wsRequestPath.solutionName + " -> " + wsRequestPath.formName);
	}

	private Object checkAuthorization(HttpServletRequest request, IClientPluginAccess client, String solutionName, String formName) throws Exception
	{
		String[] authorizedGroups = plugin.getAuthorizedGroups();
		FunctionDefinition fd = new FunctionDefinition(formName, WS_AUTHENTICATE);
		Exist authMethodExists = fd.exists(client);
		if (authorizedGroups == null && authMethodExists != FunctionDefinition.Exist.METHOD_FOUND)
		{
			plugin.log.debug("No authorization to check, allow all access");
			return null;
		}

		//Process authentication Header
		String authorizationHeader = request.getHeader("Authorization");
		String user = null;
		String password = null;
		if (authorizationHeader != null)
		{
			if (authorizationHeader.toLowerCase().startsWith("basic "))
			{
				String authorization = authorizationHeader.substring(6);
				// TODO: which encoding to use? see http://tools.ietf.org/id/draft-reschke-basicauth-enc-05.xml
				// we assume now we get UTF-8 , we need to define a standard due to mobile client usage
				authorization = new String(Utils.decodeBASE64(authorization), "UTF-8");
				int index = authorization.indexOf(':');
				if (index > 0)
				{
					user = authorization.substring(0, index);
					password = authorization.substring(index + 1);
				}
			}
			else
			{
				plugin.log.debug("No or unsupported Authorization header");
			}
		}
		else
		{
			plugin.log.debug("No Authorization header");
		}

		if (user == null || password == null || user.trim().length() == 0 || password.trim().length() == 0)
		{
			plugin.log.debug("No credentials to proceed with authentication");
			throw new NotAuthenticatedException(solutionName);
		}

		//Process the Authentication Header values
		if (authMethodExists == FunctionDefinition.Exist.METHOD_FOUND)
		{
			//TODO: we should cache the (user,pass,retval) for an hour (across all rest clients), and not invoke WS_AUTHENTICATE function each time! (since authenticate might be expensive like LDAP)
			Object retval = fd.executeSync(client, new String[] { user, password });
			if (retval != null && !Boolean.FALSE.equals(retval) && retval != Undefined.instance)
			{
				return retval instanceof Boolean ? null : retval;
			}
			if (plugin.log.isDebugEnabled()) plugin.log.debug("Authentication method " + WS_AUTHENTICATE + " denied authentication");
			throw new NotAuthenticatedException(solutionName);
		}

		String userUid = plugin.getServerAccess().checkPasswordForUserName(user, password);
		if (userUid == null)
		{
			plugin.log.debug("Supplied credentails not valid");
			throw new NotAuthenticatedException(user);
		}

		String[] userGroups = plugin.getServerAccess().getUserGroups(userUid);
		// find a match in groups
		if (userGroups != null)
		{
			for (String ug : userGroups)
			{
				for (String ag : authorizedGroups)
				{
					if (ag.trim().equals(ug))
					{
						if (plugin.log.isDebugEnabled())
						{
							plugin.log.debug("Authorized access for user " + user + ", group " + ug);
						}
						return null;
					}
				}
			}
		}

		// no match
		throw new NotAuthorizedException("User not authorized");
	}

	private byte[] getBody(HttpServletRequest request) throws IOException
	{
		InputStream is = null;
		try
		{
			is = request.getInputStream();
			ByteArrayOutputStream baos = new ByteArrayOutputStream();

			byte[] buffer = new byte[128];
			int length;
			while ((length = is.read(buffer)) >= 0)
			{
				baos.write(buffer, 0, length);
			}

			return baos.toByteArray();
		}
		finally
		{
			if (is != null)
			{
				is.close();
			}
		}
	}

	private int getContentType(String headerValue)
	{
		if (headerValue != null)
		{
			String header = headerValue.toLowerCase();
			if (header.indexOf("json") >= 0)
			{
				return CONTENT_JSON;
			}
			if (header.indexOf("vnd.openxmlformats-officedocument") >= 0)
			{
				//  note: this content type contains 'xml' but is not XML.
				return CONTENT_BINARY;
			}
			if (header.indexOf("xml") >= 0)
			{
				return CONTENT_XML;
			}
			if (header.indexOf("text") >= 0)
			{
				return CONTENT_TEXT;
			}
			if (header.indexOf("multipart") >= 0)
			{
				return CONTENT_MULTIPART;
			}
			if (header.indexOf("application/x-www-form-urlencoded") >= 0)
			{
				return CONTENT_FORMPOST;
			}
			if (header.indexOf("octet-stream") >= 0 || header.indexOf("application") >= 0)
			{
				return CONTENT_BINARY;
			}
		}

		return CONTENT_OTHER;
	}

	private int getRequestContentType(HttpServletRequest request, String header, byte[] contents, int defaultContentType) throws UnsupportedEncodingException
	{
		String contentTypeHeaderValue = request.getHeader(header);
		int contentType = getContentType(contentTypeHeaderValue);
		if (contentType != CONTENT_OTHER) return contentType;
		if (contents != null)
		{
			String stringContent = new String(contents, getHeaderKey(request.getHeader("Content-Type"), "charset", CHARSET_DEFAULT));
			return guessContentType(stringContent, defaultContentType);
		}
		return defaultContentType;
	}

	private int guessContentType(String stringContent, int defaultContentType)
	{
		if (stringContent != null && stringContent.length() > 0)
		{
			// start guessing....
			if (stringContent.charAt(0) == '<')
			{
				return CONTENT_XML;
			}
			if (stringContent.charAt(0) == '{')
			{
				return CONTENT_JSON;
			}
		}
		return defaultContentType;
	}

	/**
	 *
	 * Gets the key from a header . For example, the following header :<br/>
	 * <b>Content-Disposition: form-data; name="myFile"; filename="SomeRandomFile.txt"</b>
	 * <br/>
	 * calling getHeaderKey(header,"name","--") will return <b>myFile<b/>
	 */
	private String getHeaderKey(String header, String key, String defaultValue)
	{
		if (header != null)
		{
			String[] split = header.split("; *");
			for (String element : split)
			{
				if (element.toLowerCase().startsWith(key + "="))
				{
					String charset = element.substring(key.length() + 1);
					if (charset.length() > 1 && charset.charAt(0) == '"' && charset.charAt(charset.length() - 1) == '"')
					{
						charset = charset.substring(1, charset.length() - 1);
					}
					return charset;
				}
			}
		}
		return defaultValue;
	}

	/**
	 *  Gets the custom header's : servoy.userproperties  value and sets the user properties with its value.
	 *  This custom header simulates a session cookie.
	 *  happens at the beginning  of each request (before application is invoked)
	 */
	void setApplicationUserProperties(HttpServletRequest request, IClientPluginAccess client)
	{
		String headerValue = request.getHeader(WS_USER_PROPERTIES_HEADER);
		if (headerValue != null)
		{
			Map<String, String> map = new HashMap<String, String>();
			org.json.JSONObject object;
			try
			{
				object = new org.json.JSONObject(headerValue);
				for (Object key : Utils.iterate(object.keys()))
				{
					String value = object.getString((String)key);
					map.put((String)key, value);
				}
				client.setUserProperties(map);
			}
			catch (JSONException e)
			{
				Debug.error("cannot get json object from " + WS_USER_PROPERTIES_HEADER + " header: ", e);
			}
		}
		else
		{
			Cookie[] cookies = request.getCookies();
			Map<String, String> map = new HashMap<String, String>();
			if (cookies != null)
			{
				for (Cookie cookie : cookies)
				{
					String name = cookie.getName();
					if (name.startsWith(WS_USER_PROPERTIES_COOKIE_PREFIX))
					{
						String value = cookie.getValue();
						map.put(name.substring(WS_USER_PROPERTIES_COOKIE_PREFIX.length()), Utils_decodeCookieValue(value));
					}
				}
				client.setUserProperties(map);
			}
		}

	}

	/**
	 * Serializes user properties as a json string header   ("servoy.userproperties" header)
	 * AND besides the custom header they are also serialized cookies for non mobile clients
	 * @param request TODO
	 *
	 */
	void setResponseUserProperties(HttpServletRequest request, HttpServletResponse response, IClientPluginAccess client)
	{
		Map<String, String> map = client.getUserProperties();
		if (map.keySet().size() > 0)
		{
			// set custom header
			try
			{
				org.json.JSONStringer stringer = new org.json.JSONStringer();
				org.json.JSONWriter writer = stringer.object();
				for (String propName : map.keySet())
				{
					writer = writer.key(propName).value(map.get(propName));
				}
				writer.endObject();
				response.setHeader(WS_USER_PROPERTIES_HEADER, writer.toString());
			}
			catch (JSONException e)
			{
				Debug.error("cannot serialize json object to " + WS_USER_PROPERTIES_HEADER + " headder: ", e);
			}
			//set cookie
			for (String propName : map.keySet())
			{
				Cookie cookie = new Cookie(WS_USER_PROPERTIES_COOKIE_PREFIX + propName, Utils_encodeCookieValue(map.get(propName)));
				String ctxPath = request.getContextPath();
				if (ctxPath == null || ctxPath.equals("/") || ctxPath.length() < 1) ctxPath = "";
				cookie.setPath(ctxPath + request.getServletPath() + "/" + RestWSPlugin.WEBSERVICE_NAME + "/" + client.getSolutionName());
				if (request.isSecure()) cookie.setSecure(true);
				response.addCookie(cookie);
			}
		}
	}

	private Object decodeContent(String contentTypeStr, int contentType, byte[] contents, String charset) throws Exception
	{
		switch (contentType)
		{
			case CONTENT_JSON :
				return plugin.getJSONSerializer().fromJSON(new String(contents, charset));

			case CONTENT_XML :
				return plugin.getJSONSerializer().fromJSON(XML.toJSONObject(new String(contents, charset)));

			case CONTENT_MULTIPART :
				return getMultipartContent(contentTypeStr, contents);

			case CONTENT_FORMPOST :
				return parseQueryString(new String(contents, charset));

			case CONTENT_BINARY :
				return contents;

			case CONTENT_TEXT :
				return new String(contents, charset);

			case CONTENT_OTHER :
				return contents;
		}

		// should not happen, content type was checked before
		throw new IllegalStateException();
	}

	private Object getMultipartContent(String contentTypeStr, byte[] contents) throws MessagingException, IOException, Exception
	{
		javax.mail.internet.MimeMultipart m = new MimeMultipart(new ServletMultipartDataSource(new ByteArrayInputStream(contents), contentTypeStr));
		Object[] partArray = new Object[m.getCount()];
		for (int i = 0; i < m.getCount(); i++)
		{
			BodyPart bodyPart = m.getBodyPart(i);
			JSMap<String, Object> partObj = new JSMap<String, Object>();
			//filename
			if (bodyPart.getFileName() != null) partObj.put("fileName", bodyPart.getFileName());
			String partContentType = "";
			//charset
			if (bodyPart.getContentType() != null) partContentType = bodyPart.getContentType();

			String _charset = getHeaderKey(partContentType, "charset", "");
			partContentType = partContentType.replaceAll("(.*?);\\s*\\w+=.*", "$1");
			//contentType
			if (partContentType.length() > 0)
			{
				partObj.put("contentType", partContentType);
			}
			if (_charset.length() > 0)
			{
				partObj.put("charset", _charset);
			}
			else
			{
				_charset = "UTF-8"; // still use a valid default encoding in case it's not specified for reading it - it is ok that it will not be reported to JS I guess (this happens almost all the time)
			}
			InputStream contentStream = bodyPart.getInputStream();
			try
			{
				if (contentStream.available() > 0)
				{
					//Get content value
					Object decodedBodyPart = decodeContent(partContentType, getContentType(partContentType), Utils.getBytesFromInputStream(contentStream),
						_charset);
					contentStream.close();
					partObj.put("value", decodedBodyPart);
				}
			}
			finally
			{
				contentStream.close();
			}

			// Get name header
			String nameHeader = "";
			String[] nameHeaders = bodyPart.getHeader("Content-Disposition");
			if (nameHeaders != null)
			{
				for (String bodyName : nameHeaders)
				{
					String name = getHeaderKey(bodyName, "name", "");
					if (name.length() > 0) nameHeader = name;
					break;
				}
			}
			if (nameHeader.length() > 0) partObj.put("name", nameHeader);
			partArray[i] = partObj;
		}
		return partArray;
	}

	private Object parseQueryString(String queryString)
	{
		List<JSMap<String, Object>> args = new ArrayList<JSMap<String, Object>>();
		List<NameValuePair> values = URLEncodedUtils.parse(queryString, Charset.forName("UTF-8"));
		for (NameValuePair pair : values)
		{
			// create an array of objects, similar to multipart form posts
			JSMap<String, Object> jsmap = new JSMap<String, Object>();
			jsmap.put("value", pair.getValue());
			jsmap.put("name", pair.getName());
			jsmap.put("contentType", "text/plain");
			args.add(jsmap);
		}

		return args;
	}

	private boolean getNodebugHeadderValue(HttpServletRequest request)
	{
		// when DOING cross to an url the browser first sends and extra options request with the request method  and
		//headers it will set ,before sending the actual request
		//http://stackoverflow.com/questions/1256593/jquery-why-am-i-getting-an-options-request-insted-of-a-get-request
		if (request.getMethod().equalsIgnoreCase("OPTIONS"))
		{
			String header = request.getHeader("Access-Control-Request-Headers");
			if (header != null && header.contains(WS_NODEBUG_HEADER))
			{
				return true;
			}
		}
		return request.getHeader(WS_NODEBUG_HEADER) != null;
	}

	private void sendResult(HttpServletRequest request, HttpServletResponse response, Object result, int defaultContentType) throws Exception
	{
		byte[] bytes;

		String charset;
		if (response instanceof RestWSServletResponse && ((RestWSServletResponse)response).characterEncodingSet)
		{
			// characterEncoding was set using rest_ws client plugin
			charset = response.getCharacterEncoding();
		}
		else
		{
			String contentTypeCharset = getHeaderKey(request.getHeader("Content-Type"), "charset", CHARSET_DEFAULT);
			charset = getHeaderKey(request.getHeader("Accept"), "charset", contentTypeCharset);
		}

		String resultContentType = response.getContentType();
		if (resultContentType != null)
		{
			// content type was set using rest_ws client plugin
			String content = getContent(response, result, false, getContentType(resultContentType));
			bytes = content.getBytes(charset);
		}
		else if (result instanceof byte[])
		{
			bytes = (byte[])result;
			resultContentType = getBytesContentType(request, bytes);
		}
		else
		{
			int contentType = getRequestContentType(request, "Accept", null, defaultContentType);
			if (contentType == CONTENT_BINARY)
			{
				plugin.log.error("Request for binary data was made, but the return data is not a byte array; return data is " + result);
				sendError(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
				return;
			}

			String content = getContent(response, result, true, contentType);

			switch (contentType)
			{
				case CONTENT_JSON :
					String callback = request.getParameter("callback");
					if (callback != null && !callback.equals(""))
					{
						content = callback + '(' + content + ')';
					}
					break;

				case CONTENT_XML :
					content = "<?xml version=\"1.0\" encoding=\"" + charset + "\"?>\n" + content;
					break;
				case CONTENT_MULTIPART :
				case CONTENT_FORMPOST :
					content = "";
					break;
				case CONTENT_TEXT :
					content = result != null ? result.toString() : "";
					break;
				default :
					// how can this happen...
					throw new IllegalStateException();
			}

			switch (contentType)
			{
				// multipart requests cannot respond multipart responses so treat response as json
				case CONTENT_MULTIPART :
				case CONTENT_FORMPOST :
				case CONTENT_JSON :
					resultContentType = "application/json";
					break;

				case CONTENT_XML :
					resultContentType = "application/xml";
					break;
				case CONTENT_TEXT :
					resultContentType = "text/plain";
					break;

				default :
					// how can this happen...
					throw new IllegalStateException();
			}

			resultContentType = resultContentType + ";charset=" + charset;

			response.setHeader("Content-Type", resultContentType);

			bytes = content.getBytes(charset);

		}

		response.setContentLength(bytes.length);

		ServletOutputStream outputStream = null;
		try
		{
			outputStream = response.getOutputStream();
			outputStream.write(bytes);
			outputStream.flush();
		}
		finally
		{
			if (outputStream != null)
			{
				outputStream.close();
			}
		}
	}

	/**
	 *
	 * @param response
	 * @param result
	 * @param interpretResult
	 * @param contentType
	 * @return
	 * @throws Exception
	 */
	private String getContent(HttpServletResponse response, Object result, boolean interpretResult, int contentType) throws Exception
	{
		if (result == null)
		{
			return "";
		}

		if (result instanceof XMLObject)
		{
			if (contentType == CONTENT_JSON)
			{
				return XML.toJSONObject(result.toString()).toString();
			}
			return result.toString();
		}

		if (contentType == CONTENT_XML)
		{
			Object json = plugin.getJSONSerializer().toJSON(result);
			return XML.toString(json, null);
		}

		if (interpretResult)
		{
			try
			{
				return plugin.getJSONSerializer().toJSON(result).toString();
			}
			catch (Exception e)
			{
				Debug.error("Failed to convert " + result + " to a json structure", e);
				throw e;
			}
		}

		return result.toString();
	}

	private String getBytesContentType(HttpServletRequest request, byte[] bytes)
	{
		String resultContentType;
		resultContentType = MimeTypes_getContentType(bytes);

		if (request.getHeader("Accept") != null)
		{
			String[] acceptContentTypes = request.getHeader("Accept").split(",");

			if (resultContentType == null)
			{
				// cannot determine content type, just use first from accept header
				resultContentType = getFirstNonpatternContentType(acceptContentTypes);
				if (resultContentType != null && acceptContentTypes.length > 1)
				{
					plugin.log.warn("Could not determine byte array content type, using {} from accept header {}", resultContentType,
						request.getHeader("Accept"));
				}
			}

			if (resultContentType == null)
			{
				resultContentType = "application/octet-stream"; // if still null, then set to standard
			}

			// check if content type based on bytes is in accept header
			boolean found = false;
			for (String acc : acceptContentTypes)
			{
				if (matchContentType(acc.trim().split(";")[0], resultContentType))
				{
					found = true;
					break;
				}
			}

			if (!found)
			{
				plugin.log.warn("Byte array content type {} not found in accept header {}", resultContentType, request.getHeader("Accept"));
			}
		}

		if (resultContentType == null)
		{
			resultContentType = "application/octet-stream"; // if still null, then set to standard
		}

		return resultContentType;
	}

	private static String getFirstNonpatternContentType(String[] contentTypes)
	{
		for (String acc : contentTypes)
		{
			String contentType = acc.trim().split(";")[0];
			String[] split = contentType.split("/");
			if (split.length == 2 && !split[0].equals("*") && !split[1].equals("*"))
			{
				return contentType;
			}
		}
		return null;
	}

	private static boolean matchContentType(String contentTypePattern, String contentType)
	{
		String[] patSplit = contentTypePattern.split("/");
		if (patSplit.length != 2)
		{
			return false;
		}

		String[] typeSplit = contentType.split("/");
		if (typeSplit.length != 2)
		{
			return false;
		}

		return (patSplit[0].equals("*") || patSplit[0].equalsIgnoreCase(typeSplit[0])) &&
			(patSplit[1].equals("*") || patSplit[1].equalsIgnoreCase(typeSplit[1]));
	}

	/**
	 * Copied MimeTypes.getContentType(byte[]) from Servoy 8.0
	 */
	private String MimeTypes_getContentType(byte[] data)
	{
		{
			if (data == null)
			{
				return null;
			}
			byte[] header = new byte[11];
			System.arraycopy(data, 0, header, 0, Math.min(data.length, header.length));
			int c1 = header[0] & 0xff;
			int c2 = header[1] & 0xff;
			int c3 = header[2] & 0xff;
			int c4 = header[3] & 0xff;
			int c5 = header[4] & 0xff;
			int c6 = header[5] & 0xff;
			int c7 = header[6] & 0xff;
			int c8 = header[7] & 0xff;
			int c9 = header[8] & 0xff;
			int c10 = header[9] & 0xff;
			int c11 = header[10] & 0xff;

			if (c1 == 0xCA && c2 == 0xFE && c3 == 0xBA && c4 == 0xBE)
			{
				return "application/java-vm";
			}

			if (c1 == 0xD0 && c2 == 0xCF && c3 == 0x11 && c4 == 0xE0 && c5 == 0xA1 && c6 == 0xB1 && c7 == 0x1A && c8 == 0xE1)
			{
				// if the name is set then check if it can be validated by name, because it could be a xls or powerpoint
//				String contentType = guessContentTypeFromName(name);
//				if (contentType != null)
//				{
//					return contentType;
//				}
				return "application/msword";
			}
			if (c1 == 0x25 && c2 == 0x50 && c3 == 0x44 && c4 == 0x46 && c5 == 0x2d && c6 == 0x31 && c7 == 0x2e)
			{
				return "application/pdf";
			}

			if (c1 == 0x38 && c2 == 0x42 && c3 == 0x50 && c4 == 0x53 && c5 == 0x00 && c6 == 0x01)
			{
				return "image/photoshop";
			}

			if (c1 == 0x25 && c2 == 0x21 && c3 == 0x50 && c4 == 0x53)
			{
				return "application/postscript";
			}

			if (c1 == 0xff && c2 == 0xfb && c3 == 0x30)
			{
				return "audio/mp3";
			}

			if (c1 == 0x49 && c2 == 0x44 && c3 == 0x33)
			{
				return "audio/mp3";
			}

			if (c1 == 0xAC && c2 == 0xED)
			{
				// next two bytes are version number, currently 0x00 0x05
				return "application/x-java-serialized-object";
			}

			if (c1 == '<')
			{
				if (c2 == '!' ||
					((c2 == 'h' && (c3 == 't' && c4 == 'm' && c5 == 'l' || c3 == 'e' && c4 == 'a' && c5 == 'd') ||
						(c2 == 'b' && c3 == 'o' && c4 == 'd' && c5 == 'y'))) ||
					((c2 == 'H' && (c3 == 'T' && c4 == 'M' && c5 == 'L' || c3 == 'E' && c4 == 'A' && c5 == 'D') ||
						(c2 == 'B' && c3 == 'O' && c4 == 'D' && c5 == 'Y'))))
				{
					return "text/html";
				}

				if (c2 == '?' && c3 == 'x' && c4 == 'm' && c5 == 'l' && c6 == ' ')
				{
					return "application/xml";
				}
			}

			// big and little endian UTF-16 encodings, with byte order mark
			if (c1 == 0xfe && c2 == 0xff)
			{
				if (c3 == 0 && c4 == '<' && c5 == 0 && c6 == '?' && c7 == 0 && c8 == 'x')
				{
					return "application/xml";
				}
			}

			if (c1 == 0xff && c2 == 0xfe)
			{
				if (c3 == '<' && c4 == 0 && c5 == '?' && c6 == 0 && c7 == 'x' && c8 == 0)
				{
					return "application/xml";
				}
			}

			if (c1 == 'B' && c2 == 'M')
			{
				return "image/bmp";
			}

			if (c1 == 0x49 && c2 == 0x49 && c3 == 0x2a && c4 == 0x00)
			{
				return "image/tiff";
			}

			if (c1 == 0x4D && c2 == 0x4D && c3 == 0x00 && c4 == 0x2a)
			{
				return "image/tiff";
			}

			if (c1 == 'G' && c2 == 'I' && c3 == 'F' && c4 == '8')
			{
				return "image/gif";
			}

			if (c1 == '#' && c2 == 'd' && c3 == 'e' && c4 == 'f')
			{
				return "image/x-bitmap";
			}

			if (c1 == '!' && c2 == ' ' && c3 == 'X' && c4 == 'P' && c5 == 'M' && c6 == '2')
			{
				return "image/x-pixmap";
			}

			if (c1 == 137 && c2 == 80 && c3 == 78 && c4 == 71 && c5 == 13 && c6 == 10 && c7 == 26 && c8 == 10)
			{
				return "image/png";
			}

			if (c1 == 0xFF && c2 == 0xD8 && c3 == 0xFF)
			{
				if (c4 == 0xE0)
				{
					return "image/jpeg";
				}

				/**
				 * File format used by digital cameras to store images. Exif Format can be read by any application supporting JPEG. Exif Spec can be found at:
				 * http://www.pima.net/standards/it10/PIMA15740/Exif_2-1.PDF
				 */
				if ((c4 == 0xE1) && (c7 == 'E' && c8 == 'x' && c9 == 'i' && c10 == 'f' && c11 == 0))
				{
					return "image/jpeg";
				}

				if (c4 == 0xEE)
				{
					return "image/jpg";
				}
			}

			/**
			 * According to http://www.opendesign.com/files/guestdownloads/OpenDesign_Specification_for_.dwg_files.pdf
			 * first 6 bytes are of type "AC1018" (for example) and the next 5 bytes are 0x00.
			 */
			if ((c1 == 0x41 && c2 == 0x43) && (c7 == 0x00 && c8 == 0x00 && c9 == 0x00 && c10 == 0x00 && c11 == 0x00))
			{
				return "application/acad";
			}

			if (c1 == 0x2E && c2 == 0x73 && c3 == 0x6E && c4 == 0x64)
			{
				return "audio/basic"; // .au
				// format,
				// big
				// endian
			}

			if (c1 == 0x64 && c2 == 0x6E && c3 == 0x73 && c4 == 0x2E)
			{
				return "audio/basic"; // .au
				// format,
				// little
				// endian
			}

			if (c1 == 'R' && c2 == 'I' && c3 == 'F' && c4 == 'F')
			{
				/*
				 * I don't know if this is official but evidence suggests that .wav files start with "RIFF" - brown
				 */
				return "audio/x-wav";
			}

			if (c1 == 'P' && c2 == 'K')
			{
				// its application/zip but this could be a open office thing if name is given
//				String contentType = guessContentTypeFromName(name);
//				if (contentType != null)
//				{
//					return contentType;
//				}
				return "application/zip";
			}
			return null; // guessContentTypeFromName(name);
		}
	}

	private static final String COOKIE_BASE64_PREFIX = "B64p_";

	/**
	 * Copied Utils.decodeCookieValue(String) from Servoy 8.3
	 */
	private static String Utils_decodeCookieValue(String value)
	{
		String cookieValue = value;
		if (cookieValue != null && cookieValue.startsWith(COOKIE_BASE64_PREFIX))
		{
			try
			{
				cookieValue = new BufferedReader(new InputStreamReader(
					new ByteArrayInputStream(Utils.decodeBASE64(cookieValue.substring(COOKIE_BASE64_PREFIX.length()))), "UTF-8")).readLine();
			}
			catch (UnsupportedEncodingException e)
			{
				Debug.error(e);
			}
			catch (IOException e)
			{
				Debug.error(e);
			}
		}
		return cookieValue;
	}

	/**
	 * Copied Utils.encodeCookieValue(String) from Servoy 8.3
	 */
	private static String Utils_encodeCookieValue(String value)
	{
		try
		{
			return COOKIE_BASE64_PREFIX + Utils.encodeBASE64(value.getBytes("UTF-8"));
		}
		catch (UnsupportedEncodingException e)
		{
			Debug.error(e);
			return value;
		}
	}

	/**
	 * Send the error response but prevent output of the default (html) error page
	 * @param response
	 * @param error
	 * @throws IOException
	 */
	private void sendError(HttpServletResponse response, int error) throws IOException
	{
		sendError(response, error, null);
	}

	/**
	 * Send the error response with specified error response msg
	 * @param response
	 * @param error
	 * @param errorResponse
	 * @throws IOException
	 */
	private void sendError(HttpServletResponse response, int error, String errorResponse) throws IOException
	{
		response.setStatus(error);
		if (errorResponse == null)
		{
			response.setContentLength(0);
		}
		else
		{
			int contentType = guessContentType(errorResponse, CONTENT_TEXT);
			switch (contentType)
			{
				case CONTENT_JSON :
					response.setContentType("application/json");
					break;

				case CONTENT_XML :
					response.setContentType("application/xml");
					break;

				default :
			}

			Writer w = null;
			try
			{
				w = response.getWriter();
				w.write(errorResponse);
			}
			finally
			{
				if (w != null)
				{
					w.close();
				}
			}
		}
	}

	private static class RestWSServletResponse extends HttpServletResponseWrapper
	{
		boolean characterEncodingSet;

		public RestWSServletResponse(HttpServletResponse response)
		{
			super(response);
		}

		@Override
		public void setCharacterEncoding(String charset)
		{
			characterEncodingSet = true;
			super.setCharacterEncoding(charset);
		}
	}

	public static class WsRequestPath
	{
		public final String solutionName;
		public final String formName;
		public final String[] args;

		/**
		 * @param solutionName
		 * @param formName
		 * @param args
		 */
		public WsRequestPath(String solutionName, String formName, String[] args)
		{
			this.solutionName = solutionName;
			this.formName = formName;
			this.args = args;
		}

		@Override
		public String toString()
		{
			return "WsRequest [solutionName=" + solutionName + ", formName=" + formName + ", args=" + Arrays.toString(args) + "]";
		}
	}

	public static class WebServiceException extends Exception
	{
		public final int httpResponseCode;

		public WebServiceException(String message, int httpResponseCode)
		{
			super(message);
			this.httpResponseCode = httpResponseCode;
		}
	}
}
