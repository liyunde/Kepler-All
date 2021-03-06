package com.kepler.generic.reflect.impl;

import com.kepler.config.PropertiesUtils;
import com.kepler.generic.GenericMarker;
import com.kepler.generic.impl.DefaultImported;
import com.kepler.generic.reflect.GenericService;
import com.kepler.header.Headers;
import com.kepler.header.HeadersContext;
import com.kepler.header.HeadersProcessor;
import com.kepler.id.IDGenerators;
import com.kepler.invoker.Invoker;
import com.kepler.protocol.RequestFactory;
import com.kepler.serial.Serials;
import com.kepler.service.Imported;
import com.kepler.service.Service;

/**
 * @author KimShen
 *
 */
public class DefaultService extends DefaultImported implements GenericService {

	/**
	 * 是否自动加载服务
	 */
	private static final boolean AUTOMATIC = PropertiesUtils.get(DefaultService.class.getName().toLowerCase() + ".automatic", true);

	/**
	 * 泛化恒定Class
	 */
	private final Class<?>[] classes = new Class<?>[] { DelegateArgs.class };

	private final Object[] empty = new Object[] { null };

	public DefaultService(HeadersProcessor processor, IDGenerators generators, RequestFactory factory, HeadersContext header, GenericMarker marker, Imported imported, Serials serials, Invoker invoker) {
		super(processor, generators, factory, header, marker, imported, serials, invoker);
	}

	@Override
	public Object invoke(Service service, String method, String[] classes, Object... args) throws Throwable {
		if (DefaultService.AUTOMATIC) {
			// 尝试Import服务(如果未注册)
			super.imported(service);
		}
		// 仅支持默认序列化(兼容性)
		byte serial = super.serials.def4output().serial();
		// 获取Header并标记为泛型(隐式开启Header)
		Headers headers = super.marker.mark(super.processor.process(service, super.header.get()));
		// 强制同步调用
		return super.invoker.invoke(super.factory.request(headers, service, method, false, new Object[] { new DelegateArgs(classes, args != null ? args : this.empty) }, this.classes, super.generators.get(service, method).generate(), serial));
	}
}
