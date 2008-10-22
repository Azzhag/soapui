/*
 *  soapUI, copyright (C) 2004-2008 eviware.com 
 *
 *  soapUI is free software; you can redistribute it and/or modify it under the 
 *  terms of version 2.1 of the GNU Lesser General Public License as published by 
 *  the Free Software Foundation.
 *
 *  soapUI is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without 
 *  even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. 
 *  See the GNU Lesser General Public License for more details at gnu.org.
 */
package com.eviware.soapui.impl.wsdl.monitor.jettyproxy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Enumeration;
import java.util.HashSet;

import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;

import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HostConfiguration;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpState;
import org.apache.commons.httpclient.URI;
import org.apache.commons.httpclient.methods.InputStreamRequestEntity;
import org.mortbay.util.IO;

import com.eviware.soapui.impl.wsdl.WsdlProject;
import com.eviware.soapui.impl.wsdl.actions.monitor.SoapMonitorAction.LaunchForm;
import com.eviware.soapui.impl.wsdl.monitor.JProxyServletWsdlMonitorMessageExchange;
import com.eviware.soapui.impl.wsdl.monitor.SoapMonitor;
import com.eviware.soapui.impl.wsdl.submit.transports.http.support.methods.ExtendedPostMethod;
import com.eviware.soapui.model.settings.Settings;

public class ProxyServlet implements Servlet
{

	protected ServletConfig config;
	protected ServletContext context;
	protected HttpClient client;
	protected JProxyServletWsdlMonitorMessageExchange capturedData;
	protected SoapMonitor monitor;
	protected WsdlProject project;
	protected HttpState httpState = null;
	protected Settings settings;

	static HashSet<String> dontProxyHeaders = new HashSet<String>();
	{
		dontProxyHeaders.add("proxy-connection");
		dontProxyHeaders.add("connection");
		dontProxyHeaders.add("keep-alive");
		dontProxyHeaders.add("transfer-encoding");
		dontProxyHeaders.add("te");
		dontProxyHeaders.add("trailer");
		dontProxyHeaders.add("proxy-authorization");
		dontProxyHeaders.add("proxy-authenticate");
		dontProxyHeaders.add("upgrade");
	}

	public ProxyServlet(SoapMonitor soapMonitor)
	{
		this.monitor = soapMonitor;
		this.project = monitor.getProject();
		settings = project.getSettings();
	}

	public void destroy()
	{
	}

	public ServletConfig getServletConfig()
	{
		return config;
	}

	public String getServletInfo()
	{
		return "SoapUI Monitor";
	}

	public void init(ServletConfig config) throws ServletException
	{

		this.config = config;
		this.context = config.getServletContext();

		client = new HttpClient();

	}

	public void service(ServletRequest request, ServletResponse response) throws ServletException, IOException
	{

		HttpServletRequest httpRequest = (HttpServletRequest) request;

		// for this create ui server and port, properties.
		ExtendedPostMethod postMethod = new ExtendedPostMethod();

		if (capturedData == null)
		{
			capturedData = new JProxyServletWsdlMonitorMessageExchange(project);
			capturedData.setRequestHost(httpRequest.getServerName());
			capturedData.setRequestHeader(httpRequest);
			capturedData.setTargetURL(httpRequest.getRequestURL().toString());
		}

		CaptureInputStream capture = new CaptureInputStream(httpRequest.getInputStream());

		// check connection header
		String connectionHeader = httpRequest.getHeader("Connection");
		if (connectionHeader != null)
		{
			connectionHeader = connectionHeader.toLowerCase();
			if (connectionHeader.indexOf("keep-alive") < 0 && connectionHeader.indexOf("close") < 0)
				connectionHeader = null;
		}

		// copy headers
		boolean xForwardedFor = false;
		@SuppressWarnings("unused")
		long contentLength = -1;
		Enumeration<?> headerNames = httpRequest.getHeaderNames();
		while (headerNames.hasMoreElements())
		{
			String hdr = (String) headerNames.nextElement();
			String lhdr = hdr.toLowerCase();

			if (dontProxyHeaders.contains(lhdr))
				continue;
			if (connectionHeader != null && connectionHeader.indexOf(lhdr) >= 0)
				continue;

			if ("content-length".equals(lhdr))
				contentLength = request.getContentLength();

			Enumeration<?> vals = httpRequest.getHeaders(hdr);
			while (vals.hasMoreElements())
			{
				String val = (String) vals.nextElement();
				if (val != null)
				{
					postMethod.setRequestHeader(lhdr, val);
					xForwardedFor |= "X-Forwarded-For".equalsIgnoreCase(hdr);
				}
			}
		}

		// Proxy headers
		postMethod.setRequestHeader("Via", "SoapUI Monitor");
		if (!xForwardedFor)
			postMethod.addRequestHeader("X-Forwarded-For", request.getRemoteAddr());

		postMethod.setRequestEntity(new InputStreamRequestEntity(capture, "text/xml; charset=utf-8"));
		
		HostConfiguration hostConfiguration = new HostConfiguration();
		hostConfiguration.setHost(new URI("http://"+httpRequest.getServerName()+httpRequest.getServletPath(), true));
		postMethod.setPath("http://"+httpRequest.getServerName()+httpRequest.getServletPath());
		
		if (settings.getBoolean(LaunchForm.SSLTUNNEL_REUSESTATE))
		{
			if ( httpState == null ) 
				httpState = new HttpState();
			client.executeMethod(hostConfiguration, postMethod, httpState);
		}
		else
		{
			client.executeMethod(hostConfiguration, postMethod);
		}
		
		// wait for transaction to end and store it.
		capturedData.stopCapture();
		byte[] res = postMethod.getResponseBody();
		IO.copy(new ByteArrayInputStream(postMethod.getResponseBody()), response.getOutputStream());
		capturedData.setRequest(capture.getCapturedData());
		capturedData.setResponse(res);
		capturedData.setResponseHeader(postMethod);
		capturedData.setRawRequestData(getRequestToBytes(postMethod, capture));
		capturedData.setRawResponseData(getResponseToBytes(postMethod, res));
		monitor.addMessageExchange(capturedData);
		capturedData = null;

		postMethod.releaseConnection();
	}


	private byte[] getResponseToBytes(ExtendedPostMethod postMethod, byte[] res)
	{
		String response = "";

		Header[] headers = postMethod.getResponseHeaders();
		for (Header header : headers)
		{
			response += header.toString();
		}
		response += "\n";
		response += new String(res);

		return response.getBytes();
	}

	private byte[] getRequestToBytes(ExtendedPostMethod postMethod, CaptureInputStream capture)
	{
		String request = "";

		Header[] headers = postMethod.getRequestHeaders();
		for (Header header : headers)
		{
			request += header.toString();
		}
		request += "\n";
		request += new String(capture.getCapturedData());

		return request.getBytes();
	}

}
