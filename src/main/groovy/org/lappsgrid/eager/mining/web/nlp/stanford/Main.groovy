package org.lappsgrid.eager.mining.web.nlp.stanford

import com.codahale.metrics.Meter
import com.codahale.metrics.Timer
import groovy.util.logging.Slf4j
import org.lappsgrid.cloud.opennlp.configuration.Config
import org.lappsgrid.eager.mining.core.jmx.Registry
import org.lappsgrid.rabbitmq.Message
import org.lappsgrid.rabbitmq.RabbitMQ
import org.lappsgrid.rabbitmq.topic.MailBox
import org.lappsgrid.rabbitmq.topic.PostOffice

//import org.lappsgrid.eager.mining.core.Configuration
//import org.lappsgrid.eager.mining.core.jmx.Registry
//import org.lappsgrid.eager.rabbitmq.Message
//import org.lappsgrid.eager.rabbitmq.topic.MailBox
//import org.lappsgrid.eager.rabbitmq.topic.PostOffice
import org.lappsgrid.serialization.Serializer

import java.rmi.registry.Registry
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 *
 */
@Slf4j('logger')
class Main implements MainMBean {

    static {
        Config.init()
    }


//    static final String HOST = "rabbitmq.lappsgrid.org/nlp"
//    static final String POSTOFFICE = "distributed.nlp.stanford"
    static final String MAILBOX = "pipelines"

    static final String SEGMENTER = "segmenter"
    static final String POS = "pos"
    static final String LEMMAS = "lemmas"
    static final String NER = "ner"

    /** The number of documents processed. */
    final Meter count = Registry.meter('nlp', 'count')
    /** The number of errors encountered. */
    final Meter errors = Registry.meter('nlp', 'errors')
    /** Processing time for documents. */
    final Timer timer = Registry.timer('nlp', 'timer')

    // Since we explicitly create a ThreadPoolExecutor we also need to
    // explicitly create the BlockingQueue used by the pool.
    private BlockingQueue<Runnable> queue
    ThreadPoolExecutor pool

    /** The Stanford service pipelines to be executed. */
    Map<String,Pipeline> pipelines

    /** Object used to block/wait until the queue is closed. */
    Object semaphore

    /** Where outgoing messages are sent. */
    PostOffice post

    /** Where we receive incoming messages. */
    MailBox box

    Main() {
        // Initialize the pipelines.  Stanford Core NLP claims to be
        // thread-safe so we will take them at their word.
        pipelines = [
            segmenter: Pipeline.Segmenter(),
            pos: Pipeline.Tagger(),
            lemmas: Pipeline.Lemmatizer(),
            ner: Pipeline.NamedEntityRecognizer()
        ]

        this.semaphore = new Object()
        this.post = new PostOffice(Config.STANFORD_EXCHANGE, Config.HOST)

        // Configure our thread pool executor
        queue = new LinkedBlockingQueue<>()
        int minCores = 2
        int maxCores = 2
        int totalCores = Runtime.getRuntime().availableProcessors()
        if (totalCores <= 2) {
            minCores = maxCores = totalCores
        }
        else {
            maxCores = totalCores // 2
            minCores = maxCores // 2
        }

        pool = new ThreadPoolExecutor(minCores, maxCores, 30, TimeUnit.SECONDS, queue)
    }

    void start() {

        logger.info("Staring Stanford NLP service.")
        logger.info("HOST: {}", Config.HOST)
        logger.info("EXCH: {}", Config.STANFORD_EXCHANGE)
        logger.info("ADDR: {}", MAILBOX)
        try {
            box = new MailBox(Config.STANFORD_EXCHANGE, MAILBOX, Config.HOST) {
                @Override
                void recv(String json) {
                    Message message
                    try {
                        logger.debug("Receieved message. Size: {}", json.length())
                        message = Serializer.parse(json, Message)
                    }
                    catch (Exception e) {
                        error(e.getMessage())
                        return
                    }

                    if ('shutdown' == message.command) {
                        logger.info("Received a shutdown message.")
                        stop()
                        return
                    }
                    if (message.route.size() == 0) {
                        // There is nowhere to send the result so we have nothing to do.
                        error("NLP tools were sent data but have no route defined.")
                        return
                    }

                    logger.debug("Looking up the pipeline for {}", message.command)
                    Pipeline pipeline = Main.this.pipelines[message.command]
                    if (pipeline == null) {
                        error("Invalid pipeline " + message.command)
                        return
                    }

                    logger.debug("Staring a worker.")
                    Worker worker = new Worker(pipeline, message, Main.this.post, Main.this.timer, Main.this.count, Main.this.errors)
                    Main.this.pool.execute(worker)
                }
            }
        }
        catch (Exception e) {
            logger.error("Unable to create a RabbitMQ MailBox", e)
        }
    }

    String stats() {
        return String.format("[monitor] [%d/%d/%d] Active: %d\nCompleted: %d\nTask: %d\nisShutdown: %s\nisTerminated: %s",
                this.pool.getPoolSize(),
                this.pool.getCorePoolSize(),
                this.pool.getMaximumPoolSize(),
                this.pool.getActiveCount(),
                this.pool.getCompletedTaskCount(),
                this.pool.getTaskCount(),
                this.pool.isShutdown(),
                this.pool.isTerminated())
    }

    String increase() {
        int cores = Runtime.getRuntime().availableProcessors()
        int size = pool.maximumPoolSize + 1
        pool.maximumPoolSize = size
        if (size < cores) {
            return "OK size is now " + size
        }
        return "Pool size is larger than available processors"
    }

    String decrease() {
        if (pool.maximumPoolSize > 1) {
            pool.maximumPoolSize = pool.maximumPoolSize - 1
            return "OK"
        }
        return "Already at one"
    }

    void stop() {
        logger.info("Stopping the Stanford NLP service")
        pool.shutdown()
        if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
            pool.shutdownNow()
        }

        if (box) box.close()
        if (post) post.close()
        synchronized (semaphore) {
            semaphore.notifyAll()
        }
    }

    private void error(String message) {
        logger.error(message)
        errors.mark()
    }

    static void main(String[] args) {
        Main app = new Main()
        Registry.register(app, "org.lappsgrid.eager.mining.nlp.stanford.Main:type=Main")
        Registry.startJmxReporter()
        app.start()

        // Wait until another thread calls notify() or notifyAll() on the semaphore.
        synchronized (app.semaphore) {
            app.semaphore.wait()
        }
        logger.info("Stanford NLP service terminated.")
    }
}
