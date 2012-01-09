/*
 *  soapUI, copyright (C) 2004-2011 eviware.com 
 *
 *  soapUI is free software; you can redistribute it and/or modify it under the 
 *  terms of version 2.1 of the GNU Lesser General Public License as published by 
 *  the Free Software Foundation.
 *
 *  soapUI is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without 
 *  even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. 
 *  See the GNU Lesser General Public License for more details at gnu.org.
 */

package com.eviware.soapui.impl.wsdl.support.http;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;

import org.apache.http.Header;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.params.AuthPolicy;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.impl.auth.NTLMSchemeFactory;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.protocol.HttpContext;
import org.apache.log4j.Logger;

import com.eviware.soapui.SoapUI;
import com.eviware.soapui.impl.wsdl.submit.transports.http.ExtendedHttpMethod;
import com.eviware.soapui.impl.wsdl.support.CompressionSupport;
import com.eviware.soapui.model.settings.Settings;
import com.eviware.soapui.model.settings.SettingsListener;
import com.eviware.soapui.settings.HttpSettings;
import com.eviware.soapui.settings.SSLSettings;
import com.eviware.soapui.support.StringUtils;

/**
 * HttpClient related tools
 * 
 * @author Ole.Matzura
 */

public class HttpClientSupport
{
	private final static Helper helper = new Helper();

	/**
	 * Internal helper to ensure synchronized access..
	 */

	private static class Helper
	{
		private DefaultHttpClient httpClient;
		private final static Logger log = Logger.getLogger( HttpClientSupport.Helper.class );
		private SoapUIMultiThreadedHttpConnectionManager connectionManager;
		private KeyStore keyStore;
		private SoapUISSLSocketFactory socketFactory;

		public Helper()
		{
			Settings settings = SoapUI.getSettings();
			SchemeRegistry registry = new SchemeRegistry();
			registry.register( new Scheme( "http", 80, PlainSocketFactory.getSocketFactory() ) );

			try
			{
				keyStore = initKeyStore();
				socketFactory = new SoapUISSLSocketFactory( keyStore );
				registry.register( new Scheme( "https", 443, socketFactory ) );
			}
			catch( Throwable e )
			{
				SoapUI.logError( e );
			}

			connectionManager = new SoapUIMultiThreadedHttpConnectionManager( registry );
			connectionManager.setMaxConnectionsPerHost( ( int )settings.getLong( HttpSettings.MAX_CONNECTIONS_PER_HOST,
					500 ) );
			connectionManager.setMaxTotalConnections( ( int )settings.getLong( HttpSettings.MAX_TOTAL_CONNECTIONS, 2000 ) );

			httpClient = new DefaultHttpClient( connectionManager );
			httpClient.getAuthSchemes().register( AuthPolicy.NTLM, new NTLMSchemeFactory() );
			httpClient.getAuthSchemes().register( AuthPolicy.SPNEGO, new NTLMSchemeFactory() );

			settings.addSettingsListener( new SSLSettingsListener() );
		}

		public DefaultHttpClient getHttpClient()
		{
			return httpClient;
		}

		public HttpResponse execute( ExtendedHttpMethod method, HttpContext httpContext ) throws ClientProtocolException,
				IOException
		{
			method.afterWriteRequest();
			HttpResponse httpResponse = httpClient.execute( ( HttpUriRequest )method, httpContext );
			method.setHttpResponse( httpResponse );
			return httpResponse;
		}

		public HttpResponse execute( ExtendedHttpMethod method ) throws ClientProtocolException, IOException
		{
			method.afterWriteRequest();
			HttpResponse httpResponse = httpClient.execute( ( HttpUriRequest )method );
			method.setHttpResponse( httpResponse );
			return httpResponse;
		}

		public final class SSLSettingsListener implements SettingsListener
		{
			public void settingChanged( String name, String newValue, String oldValue )
			{
				if( !StringUtils.hasContent( newValue ) )
					return;

				if( name.equals( SSLSettings.KEYSTORE ) || name.equals( SSLSettings.KEYSTORE_PASSWORD ) )
				{
					try
					{
						log.info( "Updating keyStore.." );
						initKeyStore();
					}
					catch( Throwable e )
					{
						SoapUI.logError( e );
					}
				}
				else if( name.equals( HttpSettings.MAX_CONNECTIONS_PER_HOST ) )
				{
					log.info( "Updating max connections per host to " + newValue );
					connectionManager.setMaxConnectionsPerHost( Integer.parseInt( newValue ) );
				}
				else if( name.equals( HttpSettings.MAX_TOTAL_CONNECTIONS ) )
				{
					log.info( "Updating max total connections host to " + newValue );
					connectionManager.setMaxTotalConnections( Integer.parseInt( newValue ) );
				}
			}

			@Override
			public void settingsReloaded()
			{
				try
				{
					log.info( "Updating keyStore.." );
					initKeyStore();
				}
				catch( Throwable e )
				{
					SoapUI.logError( e );
				}
			}
		}

		public KeyStore initKeyStore() throws KeyStoreException, NoSuchAlgorithmException, CertificateException,
				IOException, UnrecoverableKeyException, KeyManagementException
		{
			keyStore = KeyStore.getInstance( KeyStore.getDefaultType() );

			Settings settings = SoapUI.getSettings();

			String keyStoreUrl = System.getProperty( "soapui.ssl.keystore.location",
					settings.getString( SSLSettings.KEYSTORE, null ) );

			keyStoreUrl = keyStoreUrl != null ? keyStoreUrl.trim() : "";
			String pass = System.getProperty( "soapui.ssl.keystore.password",
					settings.getString( SSLSettings.KEYSTORE_PASSWORD, "" ) );

			char[] pwd = pass.toCharArray();

			if( keyStoreUrl.trim().length() > 0 )
			{
				File f = new File( keyStoreUrl );

				if( f.exists() )
				{
					log.info( "Initializing KeyStore" );

					FileInputStream instream = new FileInputStream( f );

					try
					{
						keyStore.load( instream, pwd );
					}
					finally
					{
						try
						{
							instream.close();
						}
						catch( Exception ignore )
						{
						}
					}
				}
			}

			return keyStore;
		}
	}

	public static DefaultHttpClient getHttpClient()
	{
		return helper.getHttpClient();
	}

	public static HttpResponse execute( ExtendedHttpMethod method, HttpContext httpContext )
			throws ClientProtocolException, IOException
	{
		return helper.execute( method, httpContext );
	}

	public static HttpResponse execute( ExtendedHttpMethod method ) throws ClientProtocolException, IOException
	{
		return helper.execute( method );
	}

	public static void applyHttpSettings( HttpRequest httpMethod, Settings settings )
	{
		// user agent?
		String userAgent = settings.getString( HttpSettings.USER_AGENT, null );
		if( userAgent != null && userAgent.length() > 0 )
			httpMethod.setHeader( "User-Agent", userAgent );

		// timeout?
		long timeout = settings.getLong( HttpSettings.SOCKET_TIMEOUT, HttpSettings.DEFAULT_SOCKET_TIMEOUT );
		httpMethod.getParams().setParameter( CoreConnectionPNames.SO_TIMEOUT, ( int )timeout );
	}

	public static String getResponseCompressionType( HttpResponse httpResponse )
	{
		Header contentType = httpResponse.getEntity().getContentType();
		Header contentEncoding = httpResponse.getEntity().getContentEncoding();

		return getCompressionType( contentType == null ? null : contentType.getValue(), contentEncoding == null ? null
				: contentEncoding.getValue() );
	}

	public static String getCompressionType( String contentType, String contentEncoding )
	{
		String compressionAlg = contentType == null ? null : CompressionSupport.getAvailableAlgorithm( contentType );
		if( compressionAlg != null )
			return compressionAlg;

		if( contentEncoding == null )
			return null;
		else
			return CompressionSupport.getAvailableAlgorithm( contentEncoding );
	}

	public static void addSSLListener( Settings settings )
	{
		settings.addSettingsListener( helper.new SSLSettingsListener() );
	}
}
