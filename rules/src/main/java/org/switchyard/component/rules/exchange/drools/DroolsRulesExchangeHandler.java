/* 
 * JBoss, Home of Professional Open Source 
 * Copyright 2011 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @author tags. All rights reserved. 
 * See the copyright.txt in the distribution for a 
 * full listing of individual contributors.
 *
 * This copyrighted material is made available to anyone wishing to use, 
 * modify, copy, or redistribute it subject to the terms and conditions 
 * of the GNU Lesser General Public License, v. 2.1. 
 * This program is distributed in the hope that it will be useful, but WITHOUT A 
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A 
 * PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more details. 
 * You should have received a copy of the GNU Lesser General Public License, 
 * v.2.1 along with this distribution; if not, write to the Free Software 
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, 
 * MA  02110-1301, USA.
 */
package org.switchyard.component.rules.exchange.drools;

import static org.switchyard.component.rules.common.RulesConstants.MESSAGE;
import static org.switchyard.component.rules.common.RulesConstants.MESSAGE_CONTENT;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.xml.namespace.QName;

import org.apache.log4j.Logger;
import org.drools.KnowledgeBase;
import org.drools.agent.KnowledgeAgent;
import org.drools.logger.KnowledgeRuntimeLogger;
import org.drools.runtime.Channel;
import org.drools.runtime.Environment;
import org.drools.runtime.KnowledgeSessionConfiguration;
import org.drools.runtime.StatefulKnowledgeSession;
import org.drools.runtime.StatelessKnowledgeSession;
import org.drools.runtime.rule.WorkingMemoryEntryPoint;
import org.switchyard.Context;
import org.switchyard.Exchange;
import org.switchyard.ExchangePattern;
import org.switchyard.ExchangePhase;
import org.switchyard.HandlerException;
import org.switchyard.Message;
import org.switchyard.ServiceReference;
import org.switchyard.common.io.resource.ResourceType;
import org.switchyard.common.type.Classes;
import org.switchyard.common.xml.XMLHelper;
import org.switchyard.component.common.rules.config.model.AuditModel;
import org.switchyard.component.common.rules.util.drools.Agents;
import org.switchyard.component.common.rules.util.drools.Audits;
import org.switchyard.component.common.rules.util.drools.Bases;
import org.switchyard.component.common.rules.util.drools.Configs;
import org.switchyard.component.rules.common.RulesActionType;
import org.switchyard.component.rules.config.model.ChannelModel;
import org.switchyard.component.rules.config.model.RulesActionModel;
import org.switchyard.component.rules.config.model.RulesComponentImplementationModel;
import org.switchyard.component.rules.event.drools.SwitchYardChannel;
import org.switchyard.component.rules.event.drools.SwitchYardServiceChannel;
import org.switchyard.component.rules.exchange.BaseRulesExchangeHandler;
import org.switchyard.exception.SwitchYardException;
import org.switchyard.metadata.ServiceOperation;

/**
 * A Drools implementation of a Rules ExchangeHandler.
 *
 * @author David Ward &lt;<a href="mailto:dward@jboss.org">dward@jboss.org</a>&gt; (C) 2011 Red Hat Inc.
 */
public class DroolsRulesExchangeHandler extends BaseRulesExchangeHandler {

    private static final Logger LOGGER = Logger.getLogger(DroolsRulesExchangeHandler.class);

    private final Lock _fireLock = new ReentrantLock();
    private final Lock _stateLock = new ReentrantLock();
    private String _targetNamespace;
    private String _messageContentName;
    private KnowledgeAgent _kagent;
    private AuditModel _audit;
    private Map<String,RulesActionModel> _actions = new HashMap<String,RulesActionModel>();
    private Map<String,Channel> _channels = new HashMap<String,Channel>();
    private Map<QName,ServiceReference> _references;
    private KnowledgeBase _kbase;
    private KnowledgeSessionConfiguration _ksessionConfig;
    private Environment _environment;
    private StatefulKnowledgeSession _ksession;
    private Thread _ksessionThread;
    private KnowledgeRuntimeLogger _klogger;

    /**
     * Constructs a new DroolsRulesExchangeHandler.
     */
    public DroolsRulesExchangeHandler() {}

    /**
     * {@inheritDoc}
     */
    @Override
    public void init(QName qname, RulesComponentImplementationModel model, Map<QName,ServiceReference> references) {
        _targetNamespace = model.getComponent().getTargetNamespace();
        _messageContentName = model.getMessageContentName();
        if (_messageContentName == null) {
            _messageContentName = MESSAGE_CONTENT;
        }
        ClassLoader loader = Classes.getClassLoader(getClass());
        ResourceType.install(loader);
        if (model.isAgent()) {
            _kagent = Agents.newAgent(model, loader);
            _kbase = _kagent.getKnowledgeBase();
        } else {
            _kbase = Bases.newBase(model, loader);
        }
        _ksessionConfig = Configs.getSessionConfiguration(model);
        _environment = Configs.getEnvironment();
        _audit = model.getAudit();
        for (RulesActionModel ram : model.getRulesActions()) {
            _actions.put(ram.getName(), ram);
        }
        for (ChannelModel cm : model.getChannels()) {
            Class<?> clazz = Classes.forName(cm.getClazz(), loader);
            if (clazz == null) {
                clazz = SwitchYardServiceChannel.class;
            }
            if (Channel.class.isAssignableFrom(clazz)) {
                @SuppressWarnings("unchecked")
                Class<? extends Channel> channelClass = (Class<? extends Channel>)clazz;
                Channel channel;
                try {
                    channel = channelClass.newInstance();
                } catch (Exception e) {
                    throw new SwitchYardException(e);
                }
                if (channel instanceof SwitchYardChannel) {
                    ((SwitchYardChannel)channel).setModel(cm);
                }
                _channels.put(cm.getName(), channel);
            } else {
                LOGGER.warn(clazz.getName() + " does not implement " + Channel.class.getName());
            }
        }
        _references = references;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start(ServiceReference serviceRef) {
        // nothing necessary
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void handleMessage(Exchange exchange) throws HandlerException {
        if (!ExchangePhase.IN.equals(exchange.getPhase())) {
            return;
        }
        Context context = exchange.getContext();
        ServiceOperation serviceOperation = exchange.getContract().getServiceOperation();
        RulesActionModel rulesActionModel = _actions.get(serviceOperation.getName());
        RulesActionType rulesActionType = getRulesActionType(context, rulesActionModel);
        Message message = exchange.getMessage();
        Object content = message.getContent();
        switch (rulesActionType) {
            case EXECUTE:
                StatelessKnowledgeSession ksessionStateless = getStatelessSession();
                KnowledgeRuntimeLogger klogger = Audits.getLogger(_audit, ksessionStateless);
                try {
                    ksessionStateless.getGlobals().set(MESSAGE, message);
                    ksessionStateless.execute(content);
                    message = (Message)ksessionStateless.getGlobals().get(MESSAGE);
                    content = message.getContent();
                } finally {
                    if (klogger != null) {
                        klogger.close();
                    }
                }
                break;
            case FIRE_ALL_RULES:
                _fireLock.lock();
                try {
                    /*
                    if (!isContinue(context)) {
                        disposeStateful();
                    }
                    */
                    StatefulKnowledgeSession ksessionStateful = getStatefulSession();
                    ksessionStateful.getGlobals().set(MESSAGE, message);
                    ksessionStateful.insert(content);
                    ksessionStateful.fireAllRules();
                    message = (Message)ksessionStateful.getGlobals().get(MESSAGE);
                    content = message.getContent();
                    if (isDispose(context)) {
                        disposeStatefulSession();
                    }
                } finally {
                    _fireLock.unlock();
                }
                break;
            case FIRE_UNTIL_HALT:
                _fireLock.lock();
                try {
                    /*
                    if (!isContinue(context)) {
                        disposeStateful();
                    }
                    */
                    boolean ksessionNew = _ksession == null;
                    final StatefulKnowledgeSession ksessionStateful = getStatefulSession();
                    content = null;
                    if (ksessionNew) {
                        final ClassLoader goodClassLoader = Classes.getClassLoader(getClass());
                        _ksessionThread = new Thread(new Runnable() {
                            public void run() {
                                Thread thread = Thread.currentThread();
                                ClassLoader origClassLoader = thread.getContextClassLoader();
                                thread.setContextClassLoader(goodClassLoader);
                                try {
                                    ksessionStateful.fireUntilHalt();
                                } catch (NullPointerException ne) {
                                    // keep checkstyle happy
                                    ne.getMessage();
                                } finally {
                                    thread.setContextClassLoader(origClassLoader);
                                }
                            }
                        });
                        _ksessionThread.setName(new StringBuilder().append(getClass().getSimpleName())
                            .append(":fireUntilHalt(").append(System.identityHashCode(ksessionStateful)).append(")").toString());
                        _ksessionThread.setDaemon(true);
                        _ksessionThread.start();
                    }
                    String ep = getEntryPoint(rulesActionModel);
                    if (ep != null) {
                        WorkingMemoryEntryPoint wmep = ksessionStateful.getWorkingMemoryEntryPoint(ep);
                        if (wmep != null) {
                            wmep.insert(content);
                        } else {
                            throw new HandlerException("Unknown entry point: " + ep + "; please check your rules source.");
                        }
                    } else {
                        ksessionStateful.insert(content);
                    }
                    if (isDispose(context)) {
                        disposeStatefulSession();
                    }
                } finally {
                    _fireLock.unlock();
                }
                break;
            default:
                throw new UnsupportedOperationException("Unsupported rules action type: " + rulesActionType);
        }
        ExchangePattern exchangePattern = serviceOperation.getExchangePattern();
        if (ExchangePattern.IN_OUT.equals(exchangePattern)) {
            message = exchange.createMessage();
            if (content != null) {
                message.setContent(content);
            }
            exchange.send(message);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stop(ServiceReference serviceRef) {
        disposeStatefulSession();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void destroy(ServiceReference serviceRef) {
        _kbase = null;
        _actions.clear();
        _channels.clear();
        _audit = null;
        _messageContentName = null;
        if (_kagent != null) {
            try {
                _kagent.dispose();
            } finally {
                _kagent = null;
            }
        }
    }

    private StatelessKnowledgeSession getStatelessSession() {
        StatelessKnowledgeSession ksessionStateless;
        if (_kagent != null) {
            ksessionStateless = _kagent.newStatelessKnowledgeSession(_ksessionConfig);
        } else {
            ksessionStateless = _kbase.newStatelessKnowledgeSession(_ksessionConfig);
        }
        return ksessionStateless;
    }

    private StatefulKnowledgeSession getStatefulSession() {
        _stateLock.lock();
        try {
            if (_ksession == null) {
                _ksession = _kbase.newStatefulKnowledgeSession(_ksessionConfig, _environment);
                _klogger = Audits.getLogger(_audit, _ksession);
                for (Map.Entry<String,Channel> entry : _channels.entrySet()) {
                    String name = entry.getKey();
                    Channel channel = entry.getValue();
                    if (name != null && channel != null) {
                        if (channel instanceof SwitchYardChannel) {
                            SwitchYardChannel syc = (SwitchYardChannel)channel;
                            QName qname = XMLHelper.createQName(_targetNamespace, syc.getModel().getReference());
                            ServiceReference reference = _references.get(qname);
                            syc.setReference(reference);
                        }
                        _ksession.registerChannel(name, channel);
                    }
                }
            }
            return _ksession;
        } finally {
            _stateLock.unlock();
        }
    }

    private void disposeStatefulSession() {
        _stateLock.lock();
        try {
            if (_ksession != null) {
                try {
                    _ksession.halt();
                } finally {
                    try {
                        _ksession.dispose();
                    } finally {
                        _ksession = null;
                        if (_klogger != null) {
                            try {
                                _klogger.close();
                            } finally {
                                _klogger = null;
                            }
                        }
                    }
                }
            }
        } finally {
            _stateLock.unlock();
        }
    }

}
