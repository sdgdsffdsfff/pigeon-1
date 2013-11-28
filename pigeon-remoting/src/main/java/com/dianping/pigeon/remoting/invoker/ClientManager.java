/**
 * Dianping.com Inc.
 * Copyright (c) 2003-2013 All Rights Reserved.
 */
package com.dianping.pigeon.remoting.invoker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;

import com.dianping.pigeon.component.HostInfo;
import com.dianping.pigeon.component.invocation.InvocationRequest;
import com.dianping.pigeon.component.phase.Disposable;
import com.dianping.pigeon.config.ConfigManager;
import com.dianping.pigeon.extension.ExtensionLoader;
import com.dianping.pigeon.monitor.LoggerLoader;
import com.dianping.pigeon.registry.RegistryManager;
import com.dianping.pigeon.registry.listener.RegistryEventListener;
import com.dianping.pigeon.registry.listener.ServiceProviderChangeEvent;
import com.dianping.pigeon.registry.listener.ServiceProviderChangeListener;
import com.dianping.pigeon.remoting.invoker.component.ConnectInfo;
import com.dianping.pigeon.remoting.invoker.config.InvokerConfig;
import com.dianping.pigeon.remoting.invoker.listener.ClusterListenerManager;
import com.dianping.pigeon.remoting.invoker.listener.DefaultClusterListener;
import com.dianping.pigeon.remoting.invoker.listener.HeartBeatListener;
import com.dianping.pigeon.remoting.invoker.listener.ReconnectListener;
import com.dianping.pigeon.remoting.invoker.route.RouteManager;
import com.dianping.pigeon.threadpool.DefaultThreadPool;
import com.dianping.pigeon.threadpool.ThreadPool;

public class ClientManager implements Disposable {

	private static final Logger logger = LoggerLoader.getLogger(ClientManager.class);

	private ClusterListenerManager clusterListenerManager = ClusterListenerManager.getInstance();

	private DefaultClusterListener clusterListener;

	private HeartBeatListener heartBeatTask;

	private ReconnectListener reconnectTask;

	private RouteManager routerManager = ExtensionLoader.getExtension(RouteManager.class);

	private ConfigManager configManager = ExtensionLoader.getExtension(ConfigManager.class);

	private ServiceProviderChangeListener providerChangeListener = new InnerServiceProviderChangeListener();

	private static ThreadPool heartBeatThreadPool = new DefaultThreadPool("Pigeon-Client-Heartbeat-ThreadPool");

	private static ThreadPool reconnectThreadPool = new DefaultThreadPool("Pigeon-Client-Reconnect-ThreadPool");

	private static ClientManager instance = new ClientManager();

	public static ClientManager getInstance() {
		return instance;
	}

	/**
	 * 
	 * @param invocationRepository
	 */
	private ClientManager() {
		this.heartBeatTask = new HeartBeatListener();
		this.reconnectTask = new ReconnectListener();
		this.clusterListener = new DefaultClusterListener(heartBeatTask, reconnectTask);
		this.clusterListenerManager.addListener(this.clusterListener);
		this.clusterListenerManager.addListener(this.heartBeatTask);
		this.clusterListenerManager.addListener(this.reconnectTask);
		heartBeatThreadPool.execute(this.heartBeatTask);
		reconnectThreadPool.execute(this.reconnectTask);
		RegistryEventListener.addListener(providerChangeListener);
	}

	public synchronized void registerClient(String serviceName, String connect, int weight) {
		this.clusterListenerManager.addConnect(new ConnectInfo(serviceName, connect, weight));
		RegistryManager.getInstance().addServiceServer(serviceName, connect, weight);
	}

	public Client getClient(InvokerConfig metaData, InvocationRequest request, List<Client> excludeClients) {
		List<Client> clientList = clusterListener.getClientList(metaData.getUrl());
		List<Client> clientsToRoute = new ArrayList<Client>(clientList);
		if (excludeClients != null) {
			clientsToRoute.removeAll(excludeClients);
		}
		return routerManager.route(clientsToRoute, metaData, request);
	}

	@Override
	public void destroy() {
		if (clusterListenerManager instanceof Disposable) {
			((Disposable) clusterListenerManager).destroy();
		}
		if (routerManager instanceof Disposable) {
			((Disposable) routerManager).destroy();
		}
		RegistryEventListener.removeListener(providerChangeListener);
	}

	/**
	 * 用Lion从ZK中获取serviceName对应的服务地址，并注册这些服务地址
	 */
	public synchronized void findAndRegisterClientFor(String serviceName, String group, String vip) {
		String serviceAddress = null;
		try {
			if (!StringUtils.isBlank(vip) && "dev".equals(configManager.getEnv())) {
				serviceAddress = vip;
			} else {
				serviceAddress = RegistryManager.getInstance().getServiceAddress(serviceName, group);
				if (serviceAddress == null && !StringUtils.isBlank(vip)) {
					serviceAddress = vip;
				}
			}
		} catch (Exception e) {
			if (StringUtils.isBlank(vip)) {
				logger.error("cannot get service client info for serviceName=" + serviceName + " no failover vip");
				throw new RuntimeException(e);
			} else {
				logger.error("cannot get service client info for serviceName=" + serviceName + " use failover vip= "
						+ vip + " instead", e);
				serviceAddress = vip;
			}
		}
		
		if (StringUtils.isBlank(serviceAddress)) {
			throw new RuntimeException("no service address found for service:" + serviceName + ",group:" + group
					+ ",vip:" + vip);
		}
		
		if (logger.isInfoEnabled()) {
			logger.info("selected service address is:" + serviceAddress + " with service:" + serviceName + ",group:"
					+ group + ",vip:" + vip);
		}
		
		serviceAddress = serviceAddress.trim();
		String[] addressList = serviceAddress.split(",");
		for (int i = 0; i < addressList.length; i++) {
			if (StringUtils.isNotBlank(addressList[i])) {
				String[] parts = addressList[i].split(":");
				String host = parts[0];

				try {
					int weight = RegistryManager.getInstance().getServiceWeight(addressList[i]);
					int port = Integer.parseInt(parts[1]);
					RegistryEventListener.providerAdded(serviceName, host, port, weight);
				} catch (Exception e) {
					throw new RuntimeException("error while getting service weight:" + addressList[i], e);
				}
			}
		}
	}

	public Map<String, Set<HostInfo>> getServiceHostInfos() {
		return RegistryManager.getInstance().getAllServiceServers();
	}

	/**
	 * @return the clusterListener
	 */
	public DefaultClusterListener getClusterListener() {
		return clusterListener;
	}

	/**
	 * @return the heartTask
	 */
	public HeartBeatListener getHeartTask() {
		return heartBeatTask;
	}

	public ReconnectListener getReconnectTask() {
		return reconnectTask;
	}

	class InnerServiceProviderChangeListener implements ServiceProviderChangeListener {
		@Override
		public void providerAdded(ServiceProviderChangeEvent event) {
			if (logger.isInfoEnabled()) {
				logger.info("add " + event.getHost() + ":" + event.getPort() + " to " + event.getServiceName());
			}
			registerClient(event.getServiceName(), event.getHost() + ":" + event.getPort(), event.getWeight());
		}

		@Override
		public void providerRemoved(ServiceProviderChangeEvent event) {
			HostInfo hostInfo = new HostInfo(event.getHost(), event.getPort(), event.getWeight());
			RegistryManager.getInstance().removeServiceServer(event.getServiceName(), hostInfo);
		}

		@Override
		public void hostWeightChanged(ServiceProviderChangeEvent event) {
		}
	}

	public void clear() {
		clusterListener.clear();
	}

}