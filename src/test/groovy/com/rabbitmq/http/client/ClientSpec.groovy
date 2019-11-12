/*
 * Copyright 2015-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.rabbitmq.http.client

import com.rabbitmq.client.*
import com.rabbitmq.http.client.domain.*
import groovy.json.JsonSlurper
import org.apache.http.HttpRequestInterceptor
import org.apache.http.auth.AuthScope
import org.apache.http.client.protocol.HttpClientContext
import org.apache.http.protocol.HttpContext
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.client.ClientHttpRequest
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RequestCallback
import org.springframework.web.client.ResponseExtractor
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestTemplate
import spock.lang.IgnoreIf
import spock.lang.Specification

import java.nio.charset.Charset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ClientSpec extends Specification {

  protected static final String DEFAULT_USERNAME = "guest"

  protected static final String DEFAULT_PASSWORD = "guest"

  protected Client client

  String brokerVersion

  private final ConnectionFactory cf = initializeConnectionFactory()

  protected static ConnectionFactory initializeConnectionFactory() {
    final cf = new ConnectionFactory()
    cf.setAutomaticRecoveryEnabled(false)
    cf
  }

  def setup() {
    client = newLocalhostNodeClient()
    client.getConnections().each { client.closeConnection(it.getName())}
    awaitAllConnectionsClosed(client)
    client.getConnections().each { println(it.getName())}
    brokerVersion = client.getOverview().getServerVersion()
  }

  protected static Client newLocalhostNodeClient() {
    new Client("http://127.0.0.1:" + managementPort() + "/api/", DEFAULT_USERNAME, DEFAULT_PASSWORD)
  }

  protected static Client newLocalhostNodeClient(HttpClientBuilderConfigurator cfg) {
    new Client("http://127.0.0.1:" + managementPort() + "/api/", DEFAULT_USERNAME, DEFAULT_PASSWORD, cfg)
  }

  static int managementPort() {
    return System.getProperty("rabbitmq.management.port") == null ?
            15672 :
            Integer.valueOf(System.getProperty("rabbitmq.management.port"))
  }

  def "GET /api/overview"() {
    when: "client requests GET /api/overview"
    final conn = openConnection()
    final ch = conn.createChannel()
    1000.times { ch.basicPublish("", "", null, null) }

    def res = client.getOverview()
    def xts = res.getExchangeTypes().collect { it.getName() }

    then: "the response is converted successfully"
    res.getNode().startsWith("rabbit@")
    res.getErlangVersion() != null

    final msgStats = res.getMessageStats()
    msgStats.basicPublish >= 0
    msgStats.publisherConfirm >= 0
    msgStats.basicDeliver >= 0
    msgStats.basicReturn >= 0

    final qTotals = res.getQueueTotals()
    qTotals.messages >= 0
    qTotals.messagesReady >= 0
    qTotals.messagesUnacknowledged >= 0

    final oTotals = res.getObjectTotals()
    oTotals.connections >= 0
    oTotals.channels >= 0
    oTotals.exchanges >= 0
    oTotals.queues >= 0
    oTotals.consumers >= 0

    res.listeners.size() >= 1
    res.contexts.size() >= 1

    xts.contains("topic")
    xts.contains("fanout")
    xts.contains("direct")
    xts.contains("headers")

    cleanup:
    if (conn.isOpen()) {
      conn.close()
    }
  }

  def "user info decoding"() {
    when: "username and password are encoded in the URL"
    def usernamePassword = new AtomicReference<>()
    def localClient = new Client("http://test+user:test%40password@localhost:" + managementPort() + "/api/", { builder ->
      builder.addInterceptorLast(new HttpRequestInterceptor() {
        @Override
        void process(org.apache.http.HttpRequest request, HttpContext context) throws org.apache.http.HttpException, IOException {
          HttpClientContext httpCtx = (HttpContext) context
          def credentials = httpCtx.getCredentialsProvider().getCredentials(new AuthScope(AuthScope.ANY_HOST, AuthScope.ANY_PORT))
          usernamePassword.set(credentials.getUserPrincipal().name + ":" + credentials.getPassword())
        }
      })
      return builder
    }
    )

    try {
      localClient.getOverview()
    } catch (Exception e) {
      // OK
    }

    then: "username and password are decoded before going into the request"
    usernamePassword.get() == "test user:test@password"
  }

  def "GET /api/nodes"() {
    when: "client retrieves a list of cluster nodes"
    final res = client.getNodes()
    final node = res.first()

    then: "the list is returned"
    res.size() >= 1
    verifyNode(node)
  }

  def "GET /api/nodes with a user-provided HTTP builder configurator"() {
    when: "a user-provided HTTP builder configurator is set"
    // this number has no particular meaning
    // but it should be enough connections for this test suite
    // and then some. MK.
    final client = newLocalhostNodeClient({ builder -> builder.setMaxConnTotal(8192) })

    and: "client retrieves a list of cluster nodes"
    final res = client.getNodes()
    final node = res.first()

    then: "the list is returned"
    res.size() >= 1
    verifyNode(node)
  }

  def "GET /api/nodes with credentials in the URL"() {
    when: "credentials are provided in the URL"
    final client = new Client("http://guest:guest@127.0.0.1:" + managementPort() + "/api/")

    and: "retrieves a list of cluster nodes"
    final res = client.getNodes()
    final node = res.first()

    then: "client connects successfully and the list is returned"
    res.size() >= 1
    verifyNode(node)
  }

  def "GET /api/nodes/{name}"() {
    when: "client retrieves a list of cluster nodes"
    final res = client.getNodes()
    final name = res.first().name
    final node = client.getNode(name)

    then: "the list is returned"
    res.size() >= 1
    verifyNode(node)
  }

  def "GET /api/connections"() {
    given: "an open RabbitMQ client connection"
    final conn = openConnection()

    when: "client retrieves a list of connections"

    final ConnectionInfo[] res = awaitEventPropagation({ client.getConnections() }) as ConnectionInfo[]
    final fst = res.first()

    then: "the list is returned"
    res.size() >= 1
    verifyConnectionInfo(fst)

    cleanup:
    conn.close()
  }

  def "GET /api/connections/{name}"() {
    given: "an open RabbitMQ client connection"
    final conn = openConnection()

    when: "client retrieves connection info with the correct name"

    final ConnectionInfo[] xs = awaitEventPropagation({ client.getConnections() }) as ConnectionInfo[]
    final x = client.getConnection(xs.first().name)

    then: "the info is returned"
    verifyConnectionInfo(x)

    cleanup:
    conn.close()
  }

  def "GET /api/connections/{name} with client-provided name"() {
    given: "an open RabbitMQ client connection with client-provided name"
    final s = UUID.randomUUID().toString()
    final conn = openConnection(s)

    when: "client retrieves connection info with the correct name"

    ConnectionInfo[] xs = awaitEventPropagation({ client.getConnections() }) as ConnectionInfo[]
    // applying filter as some previous connections can still show up the management API
    xs = xs.findAll({
      (it.clientProperties.connectionName == s)
    })
    final x = client.getConnection(xs.first().name)

    then: "the info is returned"
    verifyConnectionInfo(x)
    x.clientProperties.connectionName == s

    cleanup:
    conn.close()
  }

  def "DELETE /api/connections/{name}"() {
    given: "an open RabbitMQ client connection"
    final latch = new CountDownLatch(1)
    final s = UUID.randomUUID().toString()
    final conn = openConnection(s)
    conn.addShutdownListener({ e -> latch.countDown()})
    assert conn.isOpen()

    when: "client closes the connection"

    ConnectionInfo[] xs = awaitEventPropagation({ client.getConnections() }) as ConnectionInfo[]
    // applying filter as some previous connections can still show up the management API
    xs = xs.findAll({
      (it.clientProperties.connectionName == s)
    })
    xs.each({ client.closeConnection(it.name) })

    and: "some time passes"
    assert awaitOn(latch)

    then: "the connection is closed"
    !conn.isOpen()

    cleanup:
    if (conn.isOpen()) {
      conn.close()
    }
  }

  def "DELETE /api/connections/{name} with a user-provided reason"() {
    given: "an open RabbitMQ client connection"
    final latch = new CountDownLatch(1)
    final s = UUID.randomUUID().toString()
    final conn = openConnection(s)
    conn.addShutdownListener({e -> latch.countDown()})
    assert conn.isOpen()

    when: "client closes the connection"

    ConnectionInfo[] xs = awaitEventPropagation({ client.getConnections() }) as ConnectionInfo[]
    // applying filter as some previous connections can still show up the management API
    xs = xs.findAll({
      (it.clientProperties.connectionName == s)
    })
    xs.each({ client.closeConnection(it.name, "because reasons!") })

    and: "some time passes"
    assert awaitOn(latch)

    then: "the connection is closed"
    !conn.isOpen()

    cleanup:
    if (conn.isOpen()) {
      conn.close()
    }
  }

  def "GET /api/channels"() {
    given: "an open RabbitMQ client connection with 1 channel"
    final s = UUID.randomUUID().toString()
    final conn = openConnection(s)
    final ch = conn.createChannel()

    when: "client lists channels"

    ConnectionInfo[] xs = awaitEventPropagation({ client.getConnections() }) as ConnectionInfo[]
    // applying filter as some previous connections can still show up the management API
    xs = xs.findAll({
      (it.clientProperties.connectionName == s)
    })
    def cn = xs.first().name
    ChannelInfo[] chs = awaitEventPropagation({ client.getChannels() }) as ChannelInfo[]
    // channel name starts with the connection name
    // e.g. 127.0.0.1:42590 -> 127.0.0.1:5672 (1)
    chs = chs.findAll({ it.name.startsWith(cn) })
    final chi = chs.first()

    then: "the list is returned"
    verifyChannelInfo(chi, ch)

    cleanup:
    if (conn.isOpen()) {
      conn.close()
    }
  }

  def "GET /api/connections/{name}/channels/"() {
    given: "an open RabbitMQ client connection with 1 channel"
    final s = UUID.randomUUID().toString()
    final conn = openConnection(s)
    final ch = conn.createChannel()

    when: "client lists channels on that connection"

    ConnectionInfo[] xs = awaitEventPropagation({ client.getConnections() }) as ConnectionInfo[]
    // applying filter as some previous connections can still show up the management API
    xs = xs.findAll({
      (it.clientProperties.connectionName == s)
    })
    def cn = xs.first().name

    final ChannelInfo[] chs = awaitEventPropagation({ client.getChannels(cn) }) as ChannelInfo[]
    final chi = chs.first()

    then: "the list is returned"
    verifyChannelInfo(chi, ch)

    cleanup:
    if (conn.isOpen()) {
      conn.close()
    }
  }

  def "GET /api/channels/{name}"() {
    given: "an open RabbitMQ client connection with 1 channel"
    final s = UUID.randomUUID().toString()
    final conn = openConnection(s)
    final ch = conn.createChannel()

    when: "client retrieves channel info"

    ConnectionInfo[] xs = awaitEventPropagation({ client.getConnections() }) as ConnectionInfo[]
    // applying filter as some previous connections can still show up the management API
    xs = xs.findAll({
      (it.clientProperties.connectionName == s)
    })
    def cn = xs.first().name
    final ChannelInfo[] chs = awaitEventPropagation({ client.getChannels(cn) }) as ChannelInfo[]
    final chi = client.getChannel(chs.first().name)

    then: "the info is returned"
    verifyChannelInfo(chi, ch)

    cleanup:
    if (conn.isOpen()) {
      conn.close()
    }
  }

  def "GET /api/exchanges"() {
    when: "client retrieves the list of exchanges across all vhosts"
    final xs = client.getExchanges()
    final x = xs.first()

    then: "the list is returned"
    verifyExchangeInfo(x)
  }

  def "GET /api/exchanges/{vhost} when vhost exists"() {
    when: "client retrieves the list of exchanges in a particular vhost"
    final xs = client.getExchanges("/")

    then: "the list is returned"
    final x = xs.find { (it.name == "amq.fanout") }
    verifyExchangeInfo(x)
  }

  def "GET /api/exchanges/{vhost} when vhost DOES NOT exist"() {
    given: "vhost lolwut does not exist"
    final v = "lolwut"
    client.deleteVhost(v)

    when: "client retrieves the list of exchanges in that vhost"
    final xs = client.getExchanges(v)

    then: "null is returned"
    xs == null
  }

  def "GET /api/exchanges/{vhost}/{name} when both vhost and exchange exist"() {
    when: "client retrieves exchange amq.fanout in vhost /"
    final xs = client.getExchange("/", "amq.fanout")

    then: "exchange info is returned"
    final ExchangeInfo x = (ExchangeInfo)xs.find { it.name == "amq.fanout" && it.vhost == "/" }
    verifyExchangeInfo(x)
  }

  def "PUT /api/exchanges/{vhost}/{name} when vhost exists"() {
    given: "fanout exchange hop.test in vhost /"
    final v = "/"
    final s = "hop.test"
    client.declareExchange(v, s, new ExchangeInfo("fanout", false, false))

    when: "client lists exchanges in vhost /"
    List<ExchangeInfo> xs = client.getExchanges(v)

    then: "hop.test is listed"
    ExchangeInfo x = xs.find { (it.name == s) }
    x != null
    verifyExchangeInfo(x)

    cleanup:
    client.deleteExchange(v, s)
  }

  def "DELETE /api/exchanges/{vhost}/{name}"() {
    given: "fanout exchange hop.test in vhost /"
    final v = "/"
    final s = "hop.test"
    client.declareExchange(v, s, new ExchangeInfo("fanout", false, false))

    List<ExchangeInfo> xs = client.getExchanges(v)
    ExchangeInfo x = xs.find { (it.name == s) }
    x != null
    verifyExchangeInfo(x)

    when: "client deletes exchange hop.test in vhost /"
    client.deleteExchange(v, s)

    and: "exchange list in / is reloaded"
    xs = client.getExchanges(v)

    then: "hop.test no longer exists"
    xs.find { (it.name == s) } == null
  }

  def "POST /api/exchanges/{vhost}/{name}/publish"() {
    given: "a queue named hop.publish and a consumer on this queue"
    final v = "/"
    final conn = openConnection()
    final ch = conn.createChannel()
    final q = "hop.publish"
    ch.queueDeclare(q, false, false, false, null)
    ch.queueBind(q, "amq.direct", q)
    final latch = new CountDownLatch(1)
    final payloadReference = new AtomicReference<String>()
    final propertiesReference = new AtomicReference<AMQP.BasicProperties>()
    ch.basicConsume(q, true, {ctag, message ->
      payloadReference.set(new String(message.getBody()))
      propertiesReference.set(message.getProperties())
      latch.countDown()
    }, { ctag -> } as CancelCallback)

    when: "client publishes a message to the queue"
    final properties = new HashMap()
    properties.put("delivery_mode", 1)
    properties.put("content_type", "text/plain")
    properties.put("priority", 5)
    properties.put("headers", Collections.singletonMap("header1", "value1"))
    final routed = client.publish(v, "amq.direct", q,
            new OutboundMessage().payload("Hello world!").utf8Encoded().properties(properties))

    then: "the message is routed to the queue and consumed"
    routed
    latch.await(5, TimeUnit.SECONDS)
    payloadReference.get() == "Hello world!"
    propertiesReference.get().getDeliveryMode() == 1
    propertiesReference.get().getContentType() == "text/plain"
    propertiesReference.get().getPriority() == 5
    propertiesReference.get().getHeaders() != null
    propertiesReference.get().getHeaders().size() == 1
    propertiesReference.get().getHeaders().get("header1").toString() == "value1"

    cleanup:
    ch.queueDelete(q)
    conn.close()
  }

  def "GET /api/exchanges/{vhost}/{name}/bindings/source"() {
    given: "a queue named hop.queue1"
    final conn = openConnection()
    final ch = conn.createChannel()
    final q = "hop.queue1"
    ch.queueDeclare(q, false, false, false, null)

    when: "client lists bindings of default exchange"
    final xs = client.getBindingsBySource("/", "")

    then: "there is an automatic binding for hop.queue1"
    final x = xs.find { it.source == "" && it.destinationType == "queue" && it.destination == q }
    x != null

    cleanup:
    ch.queueDelete(q)
    conn.close()
  }

  def "GET /api/exchanges/{vhost}/{name}/bindings/destination"() {
    given: "an exchange named hop.exchange1 which is bound to amq.fanout"
    final conn = openConnection()
    final ch = conn.createChannel()
    final src = "amq.fanout"
    final dest = "hop.exchange1"
    ch.exchangeDeclare(dest, "fanout")
    ch.exchangeBind(dest, src, "")

    when: "client lists bindings of amq.fanout"
    final xs = client.getExchangeBindingsByDestination("/", dest)

    then: "there is a binding for hop.exchange1"
    final x = xs.find { it.source == src &&
        it.destinationType == "exchange" &&
        it.destination == dest
    }
    x != null

    cleanup:
    ch.exchangeDelete(dest)
    conn.close()
  }

  def "GET /api/queues"() {
    given: "at least one queue was declared"
    final Connection conn = cf.newConnection()
    final Channel ch = conn.createChannel()
    final String q = ch.queueDeclare().queue

    when: "client lists queues"
    final xs = client.getQueues()

    then: "a list of queues is returned"
    final x = xs.first()
    verifyQueueInfo(x)

    cleanup:
    ch.queueDelete(q)
    conn.close()
  }

  def "GET /api/queues/{vhost} when vhost exists"() {
    given: "at least one queue was declared in vhost /"
    final Connection conn = cf.newConnection()
    final Channel ch = conn.createChannel()
    final String q = ch.queueDeclare().queue

    when: "client lists queues"
    final xs = client.getQueues("/")

    then: "a list of queues is returned"
    final x = xs.first()
    verifyQueueInfo(x)

    cleanup:
    ch.queueDelete(q)
    conn.close()
  }

  def "GET /api/queues/{vhost} when vhost DOES NOT exist"() {
    given: "vhost lolwut DOES not exist"
    final v = "lolwut"
    client.deleteVhost(v)

    when: "client lists queues"
    final xs = client.getQueues(v)

    then: "null is returned"
    xs == null
  }

  def "GET /api/queues/{vhost}/{name} when both vhost and queue exist"() {
    given: "a queue was declared in vhost /"
    final Connection conn = cf.newConnection()
    final Channel ch = conn.createChannel()
    final String q = ch.queueDeclare().queue

    when: "client fetches info of the queue"
    final x = client.getQueue("/", q)

    then: "the info is returned"
    x.vhost == "/"
    x.name == q
    verifyQueueInfo(x)

    cleanup:
    ch.queueDelete(q)
    conn.close()
  }

  def "GET /api/queues/{vhost}/{name} with an exclusive queue"() {
    given: "an exclusive queue named hop.q1.exclusive"
    final Connection conn = cf.newConnection()
    final Channel ch = conn.createChannel()
    String s = "hop.q1.exclusive"
    ch.queueDelete(s)
    final String q = ch.queueDeclare(s, false, true, false, null).queue

    when: "client fetches info of the queue"
    final x = client.getQueue("/", q)

    then: "the queue is exclusive according to the response"
    x.exclusive

    cleanup:
    ch.queueDelete(q)
    conn.close()
  }

  def "GET /api/queues/{vhost}/{name} when queue DOES NOT exist"() {
    given: "queue lolwut does not exist in vhost /"
    final Connection conn = cf.newConnection()
    final Channel ch = conn.createChannel()
    final String q = "lolwut"
    ch.queueDelete(q)

    when: "client fetches info of the queue"
    final x = client.getQueue("/", q)

    then: "null is returned"
    x == null

    cleanup:
    ch.queueDelete(q)
    conn.close()
  }

  def "PUT /api/queues/{vhost}/{name} when vhost exists"() {
    given: "vhost /"
    final v = "/"

    when: "client declares a queue hop.test"
    final s = "hop.test"
    client.declareQueue(v, s, new QueueInfo(false, false, false))

    and: "client lists queues in vhost /"
    List<QueueInfo> xs = client.getQueues(v)

    then: "hop.test is listed"
    QueueInfo x = xs.find { (it.name == s) }
    x != null
    x.vhost == v
    x.name == s
    !x.durable
    !x.exclusive
    !x.autoDelete

    cleanup:
    client.deleteQueue(v, s)
  }

  def "PUT /api/policies/{vhost}/{name}"() {
    given: "vhost / and definition"
    final v = "/"
    final d = new HashMap<String, Object>()
    d.put("ha-mode", "all")

    when: "client declares a policy hop.test"
    final s = "hop.test"
    client.declarePolicy(v, s, new PolicyInfo(".*", 1, null, d))

    and: "client lists policies in vhost /"
    List<PolicyInfo> ps = client.getPolicies(v)

    then: "hop.test is listed"
    PolicyInfo p = ps.find { (it.name == s) }
    p != null
    p.vhost == v
    p.name == s
    p.priority == 1
    p.applyTo == "all"
    p.definition == d

    cleanup:
    client.deletePolicy(v, s)
  }

  def "PUT /api/queues/{vhost}/{name} when vhost DOES NOT exist"() {
    given: "vhost lolwut which does not exist"
    final v = "lolwut"
    client.deleteVhost(v)

    when: "client declares a queue hop.test"
    final s = "hop.test"
    client.declareQueue(v, s, new QueueInfo(false, false, false))

    then: "an exception is thrown"
    final e = thrown(HttpClientErrorException)
    e.getStatusCode() == HttpStatus.NOT_FOUND

  }

  def "DELETE /api/queues/{vhost}/{name}"() {
    final String s = UUID.randomUUID().toString()
    given: "queue ${s} in vhost /"
    final v = "/"
    client.declareQueue(v, s, new QueueInfo(false, false, false))

    List<QueueInfo> xs = client.getQueues(v)
    QueueInfo x = xs.find { (it.name == s) }
    x != null
    verifyQueueInfo(x)

    when: "client deletes queue ${s} in vhost /"
    client.deleteQueue(v, s)

    and: "queue list in / is reloaded"
    xs = client.getQueues(v)

    then: "${s} no longer exists"
    xs.find { (it.name == s) } == null
  }

  def "GET /api/bindings"() {
    given: "3 queues bound to amq.fanout"
    final Connection conn = cf.newConnection()
    final Channel ch = conn.createChannel()
    final String x  = 'amq.fanout'
    final String q1 = ch.queueDeclare().queue
    final String q2 = ch.queueDeclare().queue
    final String q3 = ch.queueDeclare().queue
    ch.queueBind(q1, x, "")
    ch.queueBind(q2, x, "")
    ch.queueBind(q3, x, "")

    when: "all queue bindings are listed"
    final List<BindingInfo> xs = client.getBindings()

    then: "amq.fanout bindings are listed"
    xs.findAll { it.destinationType == "queue" && it.source == x }
      .size() >= 3

    cleanup:
    ch.queueDelete(q1)
    ch.queueDelete(q2)
    ch.queueDelete(q3)
    conn.close()
  }

  def "GET /api/bindings/{vhost}"() {
    given: "2 queues bound to amq.topic in vhost /"
    final Connection conn = cf.newConnection()
    final Channel ch = conn.createChannel()
    final String x  = 'amq.topic'
    final String q1 = ch.queueDeclare().queue
    final String q2 = ch.queueDeclare().queue
    ch.queueBind(q1, x, "hop.*")
    ch.queueBind(q2, x, "api.test.#")

    when: "all queue bindings are listed"
    final List<BindingInfo> xs = client.getBindings("/")

    then: "amq.fanout bindings are listed"
    xs.findAll { it.destinationType == "queue" && it.source == x }
      .size() >= 2

    cleanup:
    ch.queueDelete(q1)
    ch.queueDelete(q2)
    conn.close()
  }

  def "GET /api/bindings/{vhost} example 2"() {
    given: "queues hop.test bound to amq.topic in vhost /"
    final Connection conn = cf.newConnection()
    final Channel ch = conn.createChannel()
    final String x  = 'amq.topic'
    final String q  = "hop.test"
    ch.queueDeclare(q, false, false, false, null)
    ch.queueBind(q, x, "hop.*")

    when: "all queue bindings are listed"
    final List<BindingInfo> xs = client.getBindings("/")

    then: "the amq.fanout binding is listed"
    xs.find { it.destinationType == "queue" && it.source == x && it.destination == q }

    cleanup:
    ch.queueDelete(q)
    conn.close()
  }

  def "GET /api/queues/{vhost}/{name}/bindings"() {
    given: "queues hop.test bound to amq.topic in vhost /"
    final Connection conn = cf.newConnection()
    final Channel ch = conn.createChannel()
    final String x  = 'amq.topic'
    final String q  = "hop.test"
    ch.queueDeclare(q, false, false, false, null)
    ch.queueBind(q, x, "hop.*")

    when: "all queue bindings are listed"
    final List<BindingInfo> xs = client.getQueueBindings("/", q)

    then: "the amq.fanout binding is listed"
    xs.find { it.destinationType == "queue" && it.source == x && it.destination == q }

    cleanup:
    ch.queueDelete(q)
    conn.close()
  }

  def "GET /api/bindings/{vhost}/e/:exchange/q/:queue"() {
    given: "queues hop.test bound to amq.topic in vhost /"
    final Connection conn = cf.newConnection()
    final Channel ch = conn.createChannel()
    final String x  = 'amq.topic'
    final String q  = "hop.test"
    ch.queueDeclare(q, false, false, false, null)
    ch.queueBind(q, x, "hop.*")

    when: "bindings between hop.test and amq.topic are listed"
    final List<BindingInfo> xs = client.getQueueBindingsBetween("/", x, q)

    then: "the amq.fanout binding is listed"
    final b = xs.find()
    xs.size() == 1
    b.source == x
    b.destination == q
    b.destinationType == "queue"

    cleanup:
    ch.queueDelete(q)
    conn.close()
  }

  def "GET /api/bindings/{vhost}/e/:source/e/:destination"() {
    given: "fanout exchange hop.test bound to amq.fanout in vhost /"
    final Connection conn = cf.newConnection()
    final Channel ch = conn.createChannel()
    final String s  = 'amq.fanout'
    final String d  = "hop.test"
    ch.exchangeDeclare(d, "fanout", false)
    ch.exchangeBind(d, s, "")

    when: "bindings between hop.test and amq.topic are listed"
    final List<BindingInfo> xs = client.getExchangeBindingsBetween("/", s, d)

    then: "the amq.topic binding is listed"
    final b = xs.find()
    xs.size() == 1
    b.source == s
    b.destination == d
    b.destinationType == "exchange"

    cleanup:
    ch.exchangeDelete(d)
    conn.close()
  }

  def "POST /api/bindings/{vhost}/e/:source/e/:destination"() {
    given: "fanout hop.test bound to amq.fanout in vhost /"
    final v = "/"
    final String s  = 'amq.fanout'
    final String d  = "hop.test"
    client.deleteExchange(v, d)
    client.declareExchange(v, d, new ExchangeInfo("fanout", false, false))
    client.bindExchange(v, d, s, "", [arg1: 'value1', arg2: 'value2'])

    when: "bindings between hop.test and amq.fanout are listed"
    final List<BindingInfo> xs = client.getExchangeBindingsBetween(v, s, d)

    then: "the amq.fanout binding is listed"
    final b = xs.find()
    xs.size() == 1
    b.source == s
    b.destination == d
    b.destinationType == "exchange"
    b.arguments.size() == 2
    b.arguments.containsKey("arg1")
    b.arguments.arg1 == "value1"
    b.arguments.containsKey("arg2")
    b.arguments.arg2 == "value2"

    cleanup:
    client.deleteExchange(v, d)
  }

  def "POST /api/bindings/{vhost}/e/:exchange/q/:queue"() {
    given: "queues hop.test bound to amq.topic in vhost /"
    final v = "/"
    final String x  = 'amq.topic'
    final String q  = "hop.test"
    client.declareQueue(v, q, new QueueInfo(false, false, false))
    client.bindQueue(v, q, x, "", [arg1: 'value1', arg2: 'value2'])

    when: "bindings between hop.test and amq.topic are listed"
    final List<BindingInfo> xs = client.getQueueBindingsBetween(v, x, q)

    then: "the amq.fanout binding is listed"
    final b = xs.find()
    xs.size() == 1
    b.source == x
    b.destination == q
    b.destinationType == "queue"
    b.arguments.size() == 2
    b.arguments.containsKey("arg1")
    b.arguments.arg1 == "value1"
    b.arguments.containsKey("arg2")
    b.arguments.arg2 == "value2"

    cleanup:
    client.deleteQueue(v, q)
  }

//  def "GET /api/bindings/{vhost}/e/:exchange/q/:queue/props"() {
    // TODO
//  }

//  def "DELETE /api/bindings/{vhost}/e/:exchange/q/:queue/props"() {
    // TODO
//  }

  def "POST /api/queues/{vhost}/:exchange/get"() {
    given: "a queue named hop.get and some messages in this queue"
    final v = "/"
    final conn = openConnection()
    final ch = conn.createChannel()
    final q = "hop.get"
    ch.queueDeclare(q, false, false, false, null)
    ch.confirmSelect()
    final messageCount = 5
    final properties = new AMQP.BasicProperties.Builder()
            .contentType("text/plain").deliveryMode(1).priority(5)
            .headers(Collections.singletonMap("header1", "value1"))
            .build()
    (1..messageCount).each { it ->
      ch.basicPublish("", q, properties, "payload${it}".getBytes(Charset.forName("UTF-8")))
    }
    ch.waitForConfirms(5_000)

    when: "client GETs from this queue"
    final List<InboundMessage> messages = client.get(v, q, messageCount, GetAckMode.NACK_REQUEUE_TRUE, GetEncoding.AUTO, -1)

    then: "the messages are returned"
    messages.size() == messageCount
    final message = messages.get(0)
    message.payload.startsWith("payload")
    message.payloadBytes == "payload".size() + 1
    !message.redelivered
    message.routingKey == q
    message.payloadEncoding == "string"
    message.properties != null
    message.properties.size() == 4
    message.properties.get("priority") == 5
    message.properties.get("delivery_mode") == 1
    message.properties.get("content_type") == "text/plain"
    message.properties.get("headers") != null
    message.properties.get("headers").size() == 1
    message.properties.get("headers").get("header1") == "value1"

    cleanup:
    ch.queueDelete(q)
    conn.close()
  }

  def "POST /api/queues/{vhost}/:exchange/get for one message"() {
    given: "a queue named hop.get and a message in this queue"
    final v = "/"
    final conn = openConnection()
    final ch = conn.createChannel()
    final q = "hop.get"
    ch.queueDeclare(q, false, false, false, null)
    ch.confirmSelect()
    ch.basicPublish("", q, null, "payload".getBytes(Charset.forName("UTF-8")))
    ch.waitForConfirms(5_000)

    when: "client GETs from this queue"
    final message = client.get(v, q)

    then: "the messages are returned"
    message.payload == "payload"
    message.payloadBytes == "payload".size()
    !message.redelivered
    message.routingKey == q
    message.payloadEncoding == "string"

    cleanup:
    ch.queueDelete(q)
    conn.close()
  }

  def "DELETE /api/queues/{vhost}/{name}/contents"() {
    given: "queue hop.test with 10 messages"
    final Connection conn = cf.newConnection()
    final Channel ch = conn.createChannel()
    final q = "hop.test"
    ch.queueDelete(q)
    ch.queueDeclare(q, false, false, false, null)
    ch.queueBind(q, "amq.fanout", "")
    ch.confirmSelect()
    100.times { ch.basicPublish("amq.fanout", "", null, "msg".getBytes()) }
    assert ch.waitForConfirms()
    final qi1 = ch.queueDeclarePassive(q)
    qi1.messageCount == 100

    when: "client purges the queue"
    client.purgeQueue("/", q)

    then: "the queue becomes empty"
    final qi2 = ch.queueDeclarePassive(q)
    qi2.messageCount == 0

    cleanup:
    ch.queueDelete(q)
    conn.close()
  }

//  def "POST /api/queues/{vhost}/{name}/get"() {
    // TODO
//  }

  def "GET /api/vhosts"() {
    when: "client retrieves a list of vhosts"
    final vhs = client.getVhosts()
    final vhi = vhs.first()

    then: "the info is returned"
    verifyVhost(vhi, client.getOverview().getServerVersion())
  }

  def "GET /api/vhosts/{name}"() {
    when: "client retrieves vhost info"
    final vhi = client.getVhost("/")

    then: "the info is returned"
    verifyVhost(vhi, client.getOverview().getServerVersion())
  }

  @IgnoreIf({ os.windows })
  def "PUT /api/vhosts/{name}"(String name) {
    when:
    "client creates a vhost named $name"
    client.createVhost(name)
    final vhi = client.getVhost(name)

    then: "the vhost is created"
    vhi.name == name

    cleanup:
    client.deleteVhost(name)

    where:
    name << [
        "http-created",
        "http-created2",
        "http_created",
        "http created",
        "создан по хатэтэпэ",
        "creado a través de HTTP",
        "通过http",
        "HTTP를 통해 생성",
        "HTTPを介して作成",
        "created over http?",
        "created @ http API",
        "erstellt über http",
        "http पर बनाया",
        "ถูกสร้างขึ้นผ่าน HTTP",
        "±!@^&#*"
    ]
  }

  def "PUT /api/vhosts/{name} with metadata"() {
    if (!isVersion38orLater()) return
    when: "client creates a vhost with metadata"
    final vhost = "vhost-with-metadata"
    client.deleteVhost(vhost)
    client.createVhost(vhost, true, "vhost description", "production", "application1", "realm1")
    final vhi = client.getVhost(vhost)

    then: "the vhost is created"
    vhi.name == vhost
    vhi.description == "vhost description"
    vhi.tags.size() == 3
    vhi.tags.contains("production") && vhi.tags.contains("application1") && vhi.tags.contains("realm1")
    vhi.tracing

    cleanup:
    if (isVersion38orLater()) {
      client.deleteVhost(vhost)
    }
  }

  def "DELETE /api/vhosts/{name} when vhost exists"() {
    given: "a vhost named hop-test-to-be-deleted"
    final s = "hop-test-to-be-deleted"
    client.createVhost(s)

    when: "the vhost is deleted"
    client.deleteVhost(s)

    then: "it no longer exists"
    client.getVhost(s) == null
  }

  def "DELETE /api/vhosts/{name} when vhost DOES NOT exist"() {
    given: "no vhost named hop-test-to-be-deleted"
    final s = "hop-test-to-be-deleted"
    client.deleteVhost(s)

    when: "the vhost is deleted"
    client.deleteVhost(s)

    then: "it is a no-op"
    client.getVhost(s) == null
  }

  def "GET /api/vhosts/{name}/permissions when vhost exists"() {
    when: "permissions for vhost / are listed"
    final s = "/"
    final xs = client.getPermissionsIn(s)

    then: "they include permissions for the guest user"
    UserPermissions x = xs.find { (it.user == "guest") }
    x.read == ".*"
  }

  def "GET /api/vhosts/{name}/permissions when vhost DOES NOT exist"() {
    when: "permissions for vhost trololowut are listed"
    final s = "trololowut"
    final xs = client.getPermissionsIn(s)

    then: "method returns null"
    xs == null
  }

  def "GET /api/vhosts/{name}/topic-permissions when vhost exists"() {
    if (!isVersion37orLater()) return
    when: "topic-permissions for vhost / are listed"
    final s = "/"
    final xs = client.getTopicPermissionsIn(s)

    then: "they include topic permissions for the guest user"
    TopicPermissions x = xs.find { (it.user == "guest") }
    x.exchange == "amq.topic"
    x.read == ".*"
  }

  def "GET /api/vhosts/{name}/topic-permissions when vhost DOES NOT exist"() {
    if (!isVersion37orLater()) return
    when: "topic permissions for vhost trololowut are listed"
    final s = "trololowut"
    final xs = client.getTopicPermissionsIn(s)

    then: "method returns null"
    xs == null
  }

  def "GET /api/users"() {
    when: "users are listed"
    final xs = client.getUsers()
    final version = client.getOverview().getServerVersion()

    then: "a list of users is returned"
    final x = xs.find { (it.name == "guest") }
    x.name == "guest"
    x.passwordHash != null
    isVersion36orLater(version) ? x.hashingAlgorithm != null : x.hashingAlgorithm == null
    x.tags.contains("administrator")
  }

  def "GET /api/users/{name} when user exists"() {
    when: "user guest if fetched"
    final x = client.getUser("guest")
    final version = client.getOverview().getServerVersion()

    then: "user info returned"
    x.name == "guest"
    x.passwordHash != null
    isVersion36orLater(version) ? x.hashingAlgorithm != null : x.hashingAlgorithm == null
    x.tags.contains("administrator")
  }

  def "GET /api/users/{name} when user DOES NOT exist"() {
    when: "user lolwut if fetched"
    final x = client.getUser("lolwut")

    then: "null is returned"
    x == null
  }

  def "PUT /api/users/{name} updates user tags"() {
    given: "user alt-user"
    final u = "alt-user"
    client.deleteUser(u)
    client.createUser(u, u.toCharArray(), Arrays.asList("original", "management"))
    awaitEventPropagation()

    when: "alt-user's tags are updated"
    client.updateUser(u, u.toCharArray(), Arrays.asList("management", "updated"))
    awaitEventPropagation()

    and: "alt-user info is reloaded"
    final x = client.getUser(u)

    then: "alt-user has new tags"
    x.tags.contains("updated")
    !x.tags.contains("original")
  }

  def "DELETE /api/users/{name}"() {
    given: "user alt-user"
    final u = "alt-user"
    client.deleteUser(u)
    client.createUser(u, u.toCharArray(), Arrays.asList("original", "management"))
    awaitEventPropagation()

    when: "alt-user is deleted"
    client.deleteUser(u)
    awaitEventPropagation()

    and: "alt-user info is reloaded"
    final x = client.getUser(u)

    then: "deleted user is gone"
    x == null
  }

  def "GET /api/users/{name}/permissions when user exists"() {
    when: "permissions for user guest are listed"
    final s = "guest"
    final xs = client.getPermissionsOf(s)

    then: "they include permissions for the / vhost"
    UserPermissions x = xs.find { (it.vhost == "/") }
    x.read == ".*"
  }

  def "GET /api/users/{name}/permissions when user DOES NOT exist"() {
    when: "permissions for user trololowut are listed"
    final s = "trololowut"
    final xs = client.getPermissionsOf(s)

    then: "method returns null"
    xs == null
  }

  def "GET /api/users/{name}/topic-permissions when user exists"() {
    if (!isVersion37orLater()) return
    when: "topic permissions for user guest are listed"
    final s = "guest"
    final xs = client.getTopicPermissionsOf(s)

    then: "they include topic permissions for the / vhost"
    TopicPermissions x = xs.find { (it.vhost == "/") }
    x.exchange == "amq.topic"
    x.read == ".*"
  }

  def "GET /api/users/{name}/topic-permissions when user DOES NOT exist"() {
    if (!isVersion37orLater()) return
    when: "topic permissions for user trololowut are listed"
    final s = "trololowut"
    final xs = client.getTopicPermissionsOf(s)

    then: "method returns null"
    xs == null
  }

  def "PUT /api/users/{name} with a blank password hash"() {
    given: "user alt-user with a blank password hash"
    final u = "alt-user"
    // blank password hash means only authentication using alternative
    // authentication mechanisms such as x509 certificates is possible. MK.
    final h = ""
    client.deleteUser(u)
    client.createUserWithPasswordHash(u, h.toCharArray(), Arrays.asList("original", "management"))
    client.updatePermissions("/", u, new UserPermissions(".*", ".*", ".*"))

    when: "alt-user tries to connect with a blank password"
    openConnection("alt-user", "alt-user")

    then: "connection is refused"
    // it would have a chance of being accepted if the x509 authentication mechanism was used. MK.
    thrown AuthenticationFailureException

    cleanup:
    client.deleteUser(u)
  }

  def "GET /api/whoami"() {
    when: "client retrieves active name authentication details"
    final res = client.whoAmI()

    then: "the details are returned"
    res.name == DEFAULT_USERNAME
    res.tags ==~ /administrator/
  }

  def "GET /api/permissions"() {
    when: "all permissions are listed"
    final s = "guest"
    final xs = client.getPermissions()

    then: "they include permissions for user guest in vhost /"
    final UserPermissions x = xs.find { it.vhost == "/" && it.user == s }
    x.read == ".*"
  }

  def "GET /api/permissions/{vhost}/:user when both vhost and user exist"() {
    when: "permissions of user guest in vhost / are listed"
    final u = "guest"
    final v = "/"
    final UserPermissions x = client.getPermissions(v, u)

    then: "a single permissions object is returned"
    x.read == ".*"
  }

  def "GET /api/permissions/{vhost}/:user when vhost DOES NOT exist"() {
    when: "permissions of user guest in vhost lolwut are listed"
    final u = "guest"
    final v = "lolwut"
    final UserPermissions x = client.getPermissions(v, u)

    then: "null is returned"
    x == null
  }

  def "GET /api/permissions/{vhost}/:user when username DOES NOT exist"() {
    when: "permissions of user lolwut in vhost / are lispermted"
    final u = "lolwut"
    final v = "/"
    final UserPermissions x = client.getPermissions(v, u)

    then: "null is returned"
    x == null
  }

  def "PUT /api/permissions/{vhost}/:user when both user and vhost exist"() {
    given: "vhost hop-vhost1 exists"
    final v = "hop-vhost1"
    client.createVhost(v)
    and: "user hop-user1 exists"
    final u = "hop-user1"
    client.createUser(u, "test".toCharArray(), Arrays.asList("management", "http", "policymaker"))

    when: "permissions of user guest in vhost / are updated"
    client.updatePermissions(v, u, new UserPermissions("read", "write", "configure"))

    and: "permissions are reloaded"
    final UserPermissions x = client.getPermissions(v, u)

    then: "a single permissions object is returned"
    x.read == "read"
    x.write == "write"
    x.configure == "configure"

    cleanup:
    client.deleteVhost(v)
    client.deleteUser(u)
  }

  def "PUT /api/permissions/{vhost}/:user when vhost DOES NOT exist"() {
    given: "vhost hop-vhost1 DOES NOT exist"
    final v = "hop-vhost1"
    client.deleteVhost(v)
    and: "user hop-user1 exists"
    final u = "hop-user1"
    client.createUser(u, "test".toCharArray(), Arrays.asList("management", "http", "policymaker"))

    when: "permissions of user guest in vhost / are updated"
    client.updatePermissions(v, u, new UserPermissions("read", "write", "configure"))

    then: "an exception is thrown"
    final e = thrown(HttpClientErrorException)
    e.getStatusCode() == HttpStatus.BAD_REQUEST

    cleanup:
    client.deleteUser(u)
  }

  def "DELETE /api/permissions/{vhost}/:user when both vhost and username exist"() {
    given: "vhost hop-vhost1 exists"
    final v = "hop-vhost1"
    client.createVhost(v)
    and: "user hop-user1 exists"
    final u = "hop-user1"
    client.createUser(u, "test".toCharArray(), Arrays.asList("management", "http", "policymaker"))

    and: "permissions of user guest in vhost / are set"
    client.updatePermissions(v, u, new UserPermissions("read", "write", "configure"))
    final UserPermissions x = client.getPermissions(v, u)
    x.read == "read"

    when: "permissions are cleared"
    client.clearPermissions(v, u)

    then: "no permissions are returned on reload"
    final UserPermissions y = client.getPermissions(v, u)
    y == null

    cleanup:
    client.deleteVhost(v)
    client.deleteUser(u)
  }

  def "GET /api/topic-permissions"() {
    if (!isVersion37orLater()) return
    when: "all topic permissions are listed"
    final s = "guest"
    final xs = client.getTopicPermissions()

    then: "they include topic permissions for user guest in vhost /"
    final TopicPermissions x = xs.find { it.vhost == "/" && it.user == s }
    x.exchange == "amq.topic"
    x.read == ".*"
  }

  def "GET /api/topic-permissions/{vhost}/:user when both vhost and user exist"() {
    if (!isVersion37orLater()) return
    when: "topic permissions of user guest in vhost / are listed"
    final u = "guest"
    final v = "/"
    final xs = client.getTopicPermissions(v, u)

    then: "a list of topic permissions objects is returned"
    final TopicPermissions x = xs.find { it.vhost == v && it.user == u }
    x.exchange == "amq.topic"
    x.read == ".*"
  }

  def "GET /api/topic-permissions/{vhost}/:user when vhost DOES NOT exist"() {
    if (!isVersion37orLater()) return
    when: "topic permissions of user guest in vhost lolwut are listed"
    final u = "guest"
    final v = "lolwut"
    final xs = client.getTopicPermissions(v, u)

    then: "null is returned"
    xs == null
  }

  def "GET /api/topic-permissions/{vhost}/:user when username DOES NOT exist"() {
    if (!isVersion37orLater()) return
    when: "topic permissions of user lolwut in vhost / are listed"
    final u = "lolwut"
    final v = "/"
    final xs = client.getTopicPermissions(v, u)

    then: "null is returned"
    xs == null
  }

  def "PUT /api/topic-permissions/{vhost}/:user when both user and vhost exist"() {
    given: "vhost hop-vhost1 exists"
    final v = "hop-vhost1"
    client.createVhost(v)
    and: "user hop-user1 exists"
    final u = "hop-user1"
    client.createUser(u, "test".toCharArray(), Arrays.asList("management", "http", "policymaker"))

    if (!isVersion37orLater()) return

    when: "topic permissions of user guest in vhost / are updated"
    client.updateTopicPermissions(v, u, new TopicPermissions("amq.topic", "read", "write"))

    and: "topic permissions are reloaded"
    final xs = client.getTopicPermissions(v, u)

    then: "a list with a single topiic permissions object is returned"
    xs.size() == 1
    xs.get(0).exchange == "amq.topic"
    xs.get(0).read == "read"
    xs.get(0).write == "write"

    cleanup:
    client.deleteVhost(v)
    client.deleteUser(u)
  }

  def "PUT /api/topic-permissions/{vhost}/:user when vhost DOES NOT exist"() {
    given: "vhost hop-vhost1 DOES NOT exist"
    final v = "hop-vhost1"
    client.deleteVhost(v)
    and: "user hop-user1 exists"
    final u = "hop-user1"
    client.createUser(u, "test".toCharArray(), Arrays.asList("management", "http", "policymaker"))

    if (!isVersion37orLater()) return

    when: "topic permissions of user guest in vhost / are updated"
    client.updateTopicPermissions(v, u, new TopicPermissions("amq.topic", "read", "write"))

    then: "an exception is thrown"
    final e = thrown(HttpClientErrorException)
    e.getStatusCode() == HttpStatus.BAD_REQUEST

    cleanup:
    client.deleteUser(u)
  }

  def "DELETE /api/topic-permissions/{vhost}/:user when both vhost and username exist"() {
    given: "vhost hop-vhost1 exists"
    final v = "hop-vhost1"
    client.createVhost(v)
    and: "user hop-user1 exists"
    final u = "hop-user1"
    client.createUser(u, "test".toCharArray(), Arrays.asList("management", "http", "policymaker"))

    if (!isVersion37orLater()) return

    and: "topic permissions of user guest in vhost / are set"
    client.updateTopicPermissions(v, u, new TopicPermissions("amq.topic", "read", "write"))
    final xs = client.getTopicPermissions(v, u)
    xs.size() == 1

    when: "topic permissions are cleared"
    client.clearTopicPermissions(v, u)

    then: "no topic permissions are returned on reload"
    final ys = client.getTopicPermissions(v, u)
    ys == null

    cleanup:
    client.deleteVhost(v)
    client.deleteUser(u)
  }

//  def "GET /api/parameters"() {
    // TODO
//  }

  def "GET /api/policies"() {
    given: "at least one policy was declared"
    final v = "/"
    final s = "hop.test"
    final d = new HashMap<String, Object>()
    final p = ".*"
    d.put("ha-mode", "all")
    client.declarePolicy(v, s, new PolicyInfo(p, 0, null, d))

    when: "client lists policies"
    PolicyInfo[] xs = awaitEventPropagation({ client.getPolicies() }) as PolicyInfo[]

    then: "a list of policies is returned"
    final x = xs.first()
    verifyPolicyInfo(x)

    cleanup:
    client.deletePolicy(v, s)
  }

  def "GET /api/policies/{vhost} when vhost exists"() {
    given: "at least one policy was declared in vhost /"
    final v = "/"
    final s = "hop.test"
    final d = new HashMap<String, Object>()
    final p = ".*"
    d.put("ha-mode", "all")
    client.declarePolicy(v, s, new PolicyInfo(p, 0, null, d))

    when: "client lists policies"
    PolicyInfo[] xs = awaitEventPropagation({ client.getPolicies("/") }) as PolicyInfo[]

    then: "a list of queues is returned"
    final x = xs.first()
    verifyPolicyInfo(x)

    cleanup:
    client.deletePolicy(v, s)
  }

  def "GET /api/policies/{vhost} when vhost DOES NOT exists"() {
    given: "vhost lolwut DOES not exist"
    final v = "lolwut"
    client.deleteVhost(v)

    when: "client lists policies"
    final xs = awaitEventPropagation({ client.getPolicies(v) })

    then: "null is returned"
    xs == null
  }

  def "GET /api/aliveness-test/{vhost}"() {
    when: "client performs aliveness check for the / vhost"
    final hasSucceeded = client.alivenessTest("/")

    then: "the check succeeds"
    hasSucceeded
  }

  def "GET /api/cluster-name"() {
    when: "client fetches cluster name"
    final ClusterId s = client.getClusterName()

    then: "cluster name is returned"
    s.getName() != null
  }

  def "PUT /api/cluster-name"() {
    given: "cluster name"
    final String s = client.getClusterName().name

    when: "cluster name is set to rabbit@warren"
    client.setClusterName("rabbit@warren")

    and: "cluster name is reloaded"
    final String x = client.getClusterName().name

    then: "the name is updated"
    x == "rabbit@warren"

    cleanup:
    client.setClusterName(s)
  }

  def "GET /api/extensions"() {
    given: "a node with the management plugin enabled"
    when: "client requests a list of (plugin) extensions"
    List<Map<String, Object>> xs = client.getExtensions()

    then: "a list of extensions is returned"
    !xs.isEmpty()
  }

  def "GET /api/definitions (version, vhosts, users, permissions, topic permissions)"() {
    when: "client requests the definitions"
    Definitions d = client.getDefinitions()

    then: "broker definitions are returned"
    d.getServerVersion() != null
    !d.getServerVersion().trim().isEmpty()
    !d.getVhosts().isEmpty()
    !d.getVhosts().get(0).getName().isEmpty()
    !d.getVhosts().isEmpty()
    d.getVhosts().get(0).getName() != null
    !d.getVhosts().get(0).getName().isEmpty()
    !d.getUsers().isEmpty()
    d.getUsers().get(0).getName() != null
    !d.getUsers().get(0).getName().isEmpty()
    !d.getPermissions().isEmpty()
    d.getPermissions().get(0).getUser() != null
    !d.getPermissions().get(0).getUser().isEmpty()
    if (isVersion37orLater()) {
      d.getTopicPermissions().get(0).getUser() != null
      !d.getTopicPermissions().get(0).getUser().isEmpty()
    }
  }

  def "GET /api/definitions (queues)"() {
    given: "a basic topology"
    client.declareQueue("/","queue1",new QueueInfo(false,false,false))
    client.declareQueue("/","queue2",new QueueInfo(false,false,false))
    client.declareQueue("/","queue3",new QueueInfo(false,false,false))
    when: "client requests the definitions"
    Definitions d = client.getDefinitions()

    then: "broker definitions are returned"
    !d.getQueues().isEmpty()
    d.getQueues().size() >= 3
    QueueInfo q = d.getQueues().find { it.name == "queue1" && it.vhost == "/" }
    q != null
    q.vhost == "/"
    q.name == "queue1"
    !q.durable
    !q.exclusive
    !q.autoDelete

    cleanup:
    client.deleteQueue("/","queue1")
    client.deleteQueue("/","queue2")
    client.deleteQueue("/","queue3")
  }

  def "GET /api/definitions (exchanges)"() {
    given: "a basic topology"
    client.declareExchange("/", "exchange1", new ExchangeInfo("fanout", false, false))
    client.declareExchange("/", "exchange2", new ExchangeInfo("direct", false, false))
    client.declareExchange("/", "exchange3", new ExchangeInfo("topic", false, false))
    when: "client requests the definitions"
    Definitions d = client.getDefinitions()

    then: "broker definitions are returned"
    !d.getExchanges().isEmpty()
    d.getExchanges().size() >= 3
    ExchangeInfo e = d.getExchanges().find { (it.name == "exchange1") }
    e != null
    e.vhost == "/"
    e.name == "exchange1"
    !e.durable
    !e.internal
    !e.autoDelete

    cleanup:
    client.deleteExchange("/","exchange1")
    client.deleteExchange("/","exchange2")
    client.deleteExchange("/","exchange3")
  }

  def "GET /api/definitions (bindings)"() {
    given: "a basic topology"
    client.declareQueue("/", "queue1", new QueueInfo(false, false, false))
    client.bindQueue("/", "queue1", "amq.fanout", "")
    when: "client requests the definitions"
    Definitions d = client.getDefinitions()

    then: "broker definitions are returned"
    !d.getBindings().isEmpty()
    d.getBindings().size() >= 1
    BindingInfo b = d.getBindings().find {
      it.source == "amq.fanout" && it.destination == "queue1" && it.destinationType == "queue"
    }
    b != null
    b.vhost == "/"
    b.source == "amq.fanout"
    b.destination == "queue1"
    b.destinationType == "queue"

    cleanup:
    client.deleteQueue("/","queue1")
  }

  def "PUT /api/parameters/shovel ShovelDetails.sourcePrefetchCount not sent if not set"() {
    given: "mock RestTemplate"
    def rtOriginalRef = client.rt
    client.rt = new MockRestTemplate()

    and: "a basic topology with null sourcePrefetchCount"
    ShovelDetails details = new ShovelDetails("amqp://", "amqp://", 30, true, null)
    details.setSourceQueue("queue1")
    details.setDestinationExchange("exchange1")

    when: "client declares the shovels"
    client.declareShovel("/", new ShovelInfo("shovel1", details))

    then: "the json will not include src-prefetch-count"
    def body = new JsonSlurper().parseText(client.rt.requestCaptor.body.toString())
    body.value['src-prefetch-count'] == null

    cleanup:
    client.rt = rtOriginalRef
    client.deleteShovel("/","shovel1")
  }

  def "PUT /api/parameters/shovel ShovelDetails.destinationAddTimestampHeader not sent if not set"() {
    given: "mock RestTemplate"
    def rtOriginalRef = client.rt
    client.rt = new MockRestTemplate()

    and: "a basic topology with null destinationAddTimestampHeader"
    ShovelDetails details = new ShovelDetails("amqp://", "amqp://", 30, true, null)
    details.setSourceQueue("queue1")
    details.setDestinationExchange("exchange1")

    when: "client declares the shovels"
    client.declareShovel("/", new ShovelInfo("shovel1", details))

    then: "the json will not include dest-add-timestamp-header"
    def body = new JsonSlurper().parseText(client.rt.requestCaptor.body.toString())
    body.value['dest-add-timestamp-header'] == null

    cleanup:
    client.rt = rtOriginalRef
    client.deleteShovel("/","shovel1")
    client.deleteQueue("/", "queue1")
  }

  def "GET /api/parameters/shovel"() {
    given: "a shovel defined"
    ShovelDetails value = new ShovelDetails("amqp://localhost:5672/vh1", "amqp://localhost:5672/vh2", 30, true, null)
    value.setSourceQueue("queue1")
    value.setDestinationExchange("exchange1")
    value.setSourcePrefetchCount(50L)
    value.setSourceDeleteAfter("never")
    value.setDestinationAddTimestampHeader(true)
    client.declareShovel("/", new ShovelInfo("shovel1", value))
    when: "client requests the shovels"
    final shovels = awaitEventPropagation { client.getShovels() }

    then: "shovel definitions are returned"
    !shovels.isEmpty()
    shovels.size() >= 1
    ShovelInfo s = shovels.find { (it.name == "shovel1") } as ShovelInfo
    s != null
    s.name == "shovel1"
    s.virtualHost == "/"
    s.details.sourceURIs.equals(["amqp://localhost:5672/vh1"])
    s.details.sourceExchange == null
    s.details.sourceQueue == "queue1"
    s.details.destinationURIs.equals(["amqp://localhost:5672/vh2"])
    s.details.destinationExchange == "exchange1"
    s.details.destinationQueue == null
    s.details.reconnectDelay == 30
    s.details.addForwardHeaders
    s.details.publishProperties == null
    s.details.sourcePrefetchCount == 50L
    s.details.sourceDeleteAfter == "never"
    s.details.destinationAddTimestampHeader

    cleanup:
    client.deleteShovel("/","shovel1")
    client.deleteQueue("/", "queue1")
  }

  def "GET /api/parameters/shovel with multiple URIs"() {
    given: "a shovel defined with multiple URIs"
    ShovelDetails value = new ShovelDetails(["amqp://localhost:5672/vh1", "amqp://localhost:5672/vh3"], ["amqp://localhost:5672/vh2", "amqp://localhost:5672/vh4"], 30, true, null)
    value.setSourceQueue("queue1")
    value.setDestinationExchange("exchange1")
    value.setSourcePrefetchCount(50L)
    value.setSourceDeleteAfter("never")
    value.setDestinationAddTimestampHeader(true)
    client.declareShovel("/", new ShovelInfo("shovel2", value))
    when: "client requests the shovels"
    final shovels = awaitEventPropagation { client.getShovels() }

    then: "shovel definitions are returned"
    !shovels.isEmpty()
    shovels.size() >= 1
    ShovelInfo s = shovels.find { (it.name == "shovel2") } as ShovelInfo
    s != null
    s.name == "shovel2"
    s.virtualHost == "/"
    s.details.sourceURIs.equals(["amqp://localhost:5672/vh1", "amqp://localhost:5672/vh3"])
    s.details.sourceExchange == null
    s.details.sourceQueue == "queue1"
    s.details.destinationURIs.equals(["amqp://localhost:5672/vh2", "amqp://localhost:5672/vh4"])
    s.details.destinationExchange == "exchange1"
    s.details.destinationQueue == null
    s.details.reconnectDelay == 30
    s.details.addForwardHeaders
    s.details.publishProperties == null
    s.details.sourcePrefetchCount == 50L
    s.details.sourceDeleteAfter == "never"
    s.details.destinationAddTimestampHeader

    cleanup:
    client.deleteShovel("/","shovel2")
    client.deleteQueue("/", "queue1")
  }

  def "PUT /api/parameters/shovel with an empty publish properties map"() {
    given: "a Shovel with empty publish properties"
    ShovelDetails value = new ShovelDetails("amqp://localhost:5672/vh1", "amqp://localhost:5672/vh2", 30, true, [:])
    value.setSourceQueue("queue1")
    value.setDestinationExchange("exchange1")

    when: "client tries to declare a Shovel"
    client.declareShovel("/", new ShovelInfo("shovel10", value))

    then: "an illegal argument exception is thrown"
    thrown(IllegalArgumentException)

    cleanup:
    client.deleteShovel("/","shovel1")
    client.deleteQueue("/", "queue1")
  }

  def "GET /api/shovels"() {
    given: "a basic topology"
    ShovelDetails value = new ShovelDetails("amqp://localhost:5672/vh1", "amqp://localhost:5672/vh2", 30, true, null)
    value.setSourceQueue("queue1")
    value.setDestinationExchange("exchange1")
    final shovelName = "shovel2"
    client.declareShovel("/", new ShovelInfo(shovelName, value))

    when: "client requests the shovels status"
    final shovels = awaitEventPropagation { client.getShovelsStatus() }

    then: "shovels status are returned"
    !shovels.isEmpty()
    shovels.size() >= 1
    ShovelStatus s = shovels.find { (it.name == shovelName) } as ShovelStatus
    s != null
    s.name == shovelName
    s.virtualHost == "/"
    s.type == "dynamic"
    waitAtMostUntilTrue(30, {
      ShovelStatus shovelStatus = client.getShovelsStatus().find { (it.name == shovelName) } as ShovelStatus
      shovelStatus.state == "running"
    })
    ShovelStatus status = client.getShovelsStatus().find { (it.name == shovelName) } as ShovelStatus
    status.state == "running"
    status.sourceURI == "amqp://localhost:5672/vh1"
    status.destinationURI == "amqp://localhost:5672/vh2"

    cleanup:
    client.deleteShovel("/", shovelName)
  }

  def "GET /api/parameters/federation-upstream declare and get at root vhost with non-null ack mode"() {
    given: "an upstream with non-null ack mode"
    final vhost = "/"
    final upstreamName = "upstream1"
    UpstreamDetails upstreamDetails = new UpstreamDetails()
    upstreamDetails.setUri("amqp://localhost:5672")
    upstreamDetails.setAckMode(AckMode.ON_CONFIRM)
    client.declareUpstream(vhost, upstreamName, upstreamDetails)

    when: "client requests the upstreams"
    final upstreams = awaitEventPropagation { client.getUpstreams() }

    then: "list of upstreams that contains the new upstream is returned and ack mode is correctly retrieved"
    verifyUpstreamDefinitions(vhost, upstreams, upstreamName)
    UpstreamInfo upstream = upstreams.find { (it.name == upstreamName) } as UpstreamInfo
    upstream.value.ackMode == AckMode.ON_CONFIRM

    cleanup:
    client.deleteUpstream(vhost, upstreamName)
  }

  def "GET /api/parameters/federation-upstream declare and get at root vhost"() {
    given: "an upstream"
    final vhost = "/"
    final upstreamName = "upstream1"
    declareUpstream(client, vhost, upstreamName)

    when: "client requests the upstreams"
    final upstreams = awaitEventPropagation { client.getUpstreams() }

    then: "list of upstreams that contains the new upstream is returned"
    verifyUpstreamDefinitions(vhost, upstreams, upstreamName)

    cleanup:
    client.deleteUpstream(vhost, upstreamName)
  }

  def "GET /api/parameters/federation-upstream declare and get at non-root vhost"() {
    given: "an upstream"
    final vhost = "foo"
    final upstreamName = "upstream2"
    client.createVhost(vhost)
    declareUpstream(client, vhost, upstreamName)

    when: "client requests the upstreams"
    final upstreams = awaitEventPropagation { client.getUpstreams(vhost) }

    then: "list of upstreams that contains the new upstream is returned"
    verifyUpstreamDefinitions(vhost, upstreams, upstreamName)

    cleanup:
    client.deleteUpstream(vhost,upstreamName)
    client.deleteVhost(vhost)
  }

  def "PUT /api/parameters/federation-upstream with null upstream uri"() {
    given: "an Upstream without upstream uri"
    UpstreamDetails upstreamDetails = new UpstreamDetails()

    when: "client tries to declare an Upstream"
    client.declareUpstream("/", "upstream3", upstreamDetails)

    then: "an illegal argument exception is thrown"
    thrown(IllegalArgumentException)
  }

  def "DELETE /api/parameters/federation-upstream/{vhost}/{name}"() {
    given: "upstream upstream4 in vhost /"
    final vhost = "/"
    final upstreamName = "upstream4"
    declareUpstream(client, vhost, upstreamName)

    List<UpstreamInfo> upstreams = awaitEventPropagation { client.getUpstreams() } as List<UpstreamInfo>
    verifyUpstreamDefinitions(vhost, upstreams, upstreamName)

    when: "client deletes upstream upstream4 in vhost /"
    client.deleteUpstream(vhost, upstreamName)

    and: "upstream list in / is reloaded"
    upstreams = client.getUpstreams()

    then: "upstream4 no longer exists"
    upstreams.find { (it.name == upstreamName) } == null
  }

  def "GET /api/parameters/federation-upstream-set declare and get"() {
    given: "an upstream set with two upstreams"
    final vhost = "/"
    final upstreamSetName = "upstream-set-1"
    final upstreamA = "A"
    final upstreamB = "B"
    final policyName = "federation-policy"
    declareUpstream(client, vhost, upstreamA)
    declareUpstream(client, vhost, upstreamB)
    final d1 = new UpstreamSetDetails()
    d1.setUpstream(upstreamA)
    d1.setExchange("exchangeA")
    final d2 = new UpstreamSetDetails()
    d2.setUpstream(upstreamB)
    d2.setExchange("exchangeB")
    final detailsSet = new ArrayList()
    detailsSet.add(d1)
    detailsSet.add(d2)
    client.declareUpstreamSet(vhost, upstreamSetName, detailsSet)
    PolicyInfo p = new PolicyInfo()
    p.setApplyTo("exchanges")
    p.setName(policyName)
    p.setPattern("amq\\.topic")
    p.setDefinition(Collections.singletonMap("federation-upstream-set", upstreamSetName))
    client.declarePolicy(vhost, policyName, p)

    when: "client requests the upstream set list"
    final upstreamSets = awaitEventPropagation { client.getUpstreamSets() }

    then: "upstream set with two upstreams is returned"
    !upstreamSets.isEmpty()
    UpstreamSetInfo upstreamSet = upstreamSets.find { (it.name == upstreamSetName) } as UpstreamSetInfo
    upstreamSet != null
    upstreamSet.name == upstreamSetName
    upstreamSet.vhost == vhost
    upstreamSet.component == "federation-upstream-set"
    List<UpstreamSetDetails> upstreams = upstreamSet.value
    upstreams != null
    upstreams.size() == 2
    UpstreamSetDetails responseUpstreamA = upstreams.find { (it.upstream == upstreamA) }
    responseUpstreamA != null
    responseUpstreamA.upstream == upstreamA
    responseUpstreamA.exchange == "exchangeA"
    UpstreamSetDetails responseUpstreamB = upstreams.find { (it.upstream == upstreamB) }
    responseUpstreamB != null
    responseUpstreamB.upstream == upstreamB
    responseUpstreamB.exchange == "exchangeB"

    cleanup:
    client.deletePolicy(vhost, policyName)
    client.deleteUpstreamSet(vhost,upstreamSetName)
    client.deleteUpstream(vhost,upstreamA)
    client.deleteUpstream(vhost,upstreamB)
  }

  def "PUT /api/parameters/federation-upstream-set without upstreams"() {
    given: "an Upstream without upstream uri"
    final upstreamSetDetails = new UpstreamSetDetails()
    final detailsSet = new ArrayList()
    detailsSet.add(upstreamSetDetails)

    when: "client tries to declare an Upstream"
    client.declareUpstreamSet("/", "upstrea-set-2", detailsSet)

    then: "an illegal argument exception is thrown"
    thrown(IllegalArgumentException)
  }

  protected static boolean awaitOn(CountDownLatch latch) {
    latch.await(10, TimeUnit.SECONDS)
  }

  protected static void verifyConnectionInfo(ConnectionInfo info) {
    assert info.port == ConnectionFactory.DEFAULT_AMQP_PORT
    assert !info.usesTLS
    assert info.peerHost == info.host
  }

  protected static void verifyChannelInfo(ChannelInfo chi, Channel ch) {
    assert chi.getConsumerCount() == 0
    assert chi.number == ch.getChannelNumber()
    assert chi.node.startsWith("rabbit@")
    assert chi.state == "running" ||
              chi.state == null // HTTP API may not be refreshed yet
    assert !chi.usesPublisherConfirms()
    assert !chi.transactional
  }

  protected static void verifyVhost(VhostInfo vhi, String version) {
    assert vhi.name == "/"
    assert !vhi.tracing
    assert isVersion37orLater(version) ? vhi.clusterState != null : vhi.clusterState == null
    assert isVersion38orLater(version) ? vhi.description != null : vhi.description == null
  }

  protected Connection openConnection() {
    this.cf.newConnection()
  }

  @SuppressWarnings("GrMethodMayBeStatic")
  protected Connection openConnection(String username, String password) {
    final cf = new ConnectionFactory()
    cf.setUsername(username)
    cf.setPassword(password)
    cf.newConnection()
  }

  protected Connection openConnection(String clientProvidedName) {
    this.cf.newConnection(clientProvidedName)
  }

  protected static void verifyNode(NodeInfo node) {
    assert node != null
    assert node.name != null
    assert node.socketsUsed <= node.socketsTotal
    assert node.erlangProcessesUsed <= node.erlangProcessesTotal
    assert node.erlangRunQueueLength >= 0
    assert node.memoryUsed <= node.memoryLimit
  }

  @SuppressWarnings("GrEqualsBetweenInconvertibleTypes")
  protected static void verifyExchangeInfo(ExchangeInfo x) {
    assert x.type != null
    assert x.durable != null
    assert x.name != null
    assert x.autoDelete != null
  }

  protected static void verifyPolicyInfo(PolicyInfo x) {
    assert x.name != null
    assert x.vhost != null
    assert x.pattern != null
    assert x.definition != null
    assert x.applyTo != null
  }

  @SuppressWarnings("GrEqualsBetweenInconvertibleTypes")
  protected static void verifyQueueInfo(QueueInfo x) {
    assert x.name != null
    assert x.durable != null
    assert x.exclusive != null
    assert x.autoDelete != null
  }

  static boolean isVersion36orLater(String currentVersion) {
    String v = currentVersion.replaceAll("\\+.*\$", "")
    v == "0.0.0" ? true : compareVersions(v, "3.6.0") >= 0
  }

  static boolean isVersion37orLater(String currentVersion) {
    String v = currentVersion.replaceAll("\\+.*\$", "")
    v == "0.0.0" ? true : compareVersions(v, "3.7.0") >= 0
  }

  static boolean isVersion38orLater(String currentVersion) {
    String v = currentVersion.replaceAll("\\+.*\$", "")
    v == "0.0.0" ? true : compareVersions(v, "3.8.0") >= 0
  }

  boolean isVersion37orLater() {
    return isVersion37orLater(brokerVersion)
  }

  boolean isVersion38orLater() {
    return isVersion38orLater(brokerVersion)
  }

  /**
   * https://stackoverflow.com/questions/6701948/efficient-way-to-compare-version-strings-in-java
   *
   */
  static Integer compareVersions(String str1, String str2) {
    String[] vals1 = str1.split("\\.")
    String[] vals2 = str2.split("\\.")
    int i = 0
    // set index to first non-equal ordinal or length of shortest version string
    while (i < vals1.length && i < vals2.length && vals1[i] == vals2[i]) {
      i++
    }
    // compare first non-equal ordinal number
    if (i < vals1.length && i < vals2.length) {
      if(vals1[i].indexOf('-') != -1) {
        vals1[i] = vals1[i].substring(0,vals1[i].indexOf('-'))
      }
      if(vals2[i].indexOf('-') != -1) {
        vals2[i] = vals2[i].substring(0,vals2[i].indexOf('-'))
      }
      int diff = Integer.valueOf(vals1[i]) <=> Integer.valueOf(vals2[i])
      return Integer.signum(diff)
    }
    // the strings are equal or one string is a substring of the other
    // e.g. "1.2.3" = "1.2.3" or "1.2.3" < "1.2.3.4"
    else {
      return Integer.signum(vals1.length - vals2.length)
    }
  }

  /**
   * Statistics tables in the server are updated asynchronously,
   * in particular starting with rabbitmq/rabbitmq-management#236,
   * so in some cases we need to wait before GET'ing e.g. a newly opened connection.
   */
  protected static Object awaitEventPropagation(Closure callback) {
    if (callback) {
      int n = 0
      def result = callback()
      while (result?.isEmpty() && n < 10000) {
        Thread.sleep(100)
        n += 100
        result = callback()
      }
      assert n < 10000
      result
    }
    else {
      Thread.sleep(1000)
      null
    }
  }

  protected static boolean waitAtMostUntilTrue(int timeoutInSeconds, Closure<Boolean> callback) {
    if (callback()) {
      return true
    }
    int timeout = timeoutInSeconds * 1000
    int waited = 0
    while (waited <= timeout) {
      Thread.sleep(100)
      waited += 100
      if (callback()) {
        return true
      }
    }
    false
  }

  protected static Object awaitAllConnectionsClosed(client) {
      int n = 0
      def result = client.getConnections()
      while (result?.size() > 0 && n < 10000) {
        Thread.sleep(100)
        n += 100
        result = client.getConnections()
      }
      result
  }

  protected static void declareUpstream(client, vhost, upstreamName) {
    UpstreamDetails upstreamDetails = new UpstreamDetails()
    upstreamDetails.setUri("amqp://localhost:5672")
    client.declareUpstream(vhost, upstreamName, upstreamDetails)
  }

  protected static void verifyUpstreamDefinitions(vhost, upstreams, upstreamName) {
    assert !upstreams.isEmpty()
    UpstreamInfo upstream = upstreams.find { (it.name == upstreamName) } as UpstreamInfo
    assert upstream != null
    assert upstream.name == upstreamName
    assert upstream.vhost == vhost
    assert upstream.component == "federation-upstream"
    assert upstream.value.uri == "amqp://localhost:5672"
  }

  static class MockRestTemplate extends RestTemplate {
    ClientHttpRequest requestCaptor

    @Override
    protected <T> T doExecute(URI url, HttpMethod method, RequestCallback requestCallback,
                              ResponseExtractor<T> responseExtractor) throws RestClientException {
      ClientHttpRequest request = createRequest(url, method)
      if (requestCallback != null) {
        requestCallback.doWithRequest(request)
      }
      requestCaptor = request
      return null
    }
  }

}
