package com.example;

import com.continuuity.api.Application;
import com.continuuity.api.ApplicationSpecification;
import com.continuuity.api.annotation.Handle;
import com.continuuity.api.annotation.Output;
import com.continuuity.api.annotation.ProcessInput;
import com.continuuity.api.annotation.Property;
import com.continuuity.api.annotation.UseDataSet;
import com.continuuity.api.data.dataset.KeyValueTable;
import com.continuuity.api.data.stream.Stream;
import com.continuuity.api.flow.Flow;
import com.continuuity.api.flow.FlowSpecification;
import com.continuuity.api.flow.flowlet.AbstractFlowlet;
import com.continuuity.api.flow.flowlet.Callback;
import com.continuuity.api.flow.flowlet.FailurePolicy;
import com.continuuity.api.flow.flowlet.FailureReason;
import com.continuuity.api.flow.flowlet.InputContext;
import com.continuuity.api.flow.flowlet.OutputEmitter;
import com.continuuity.api.flow.flowlet.StreamEvent;
import com.continuuity.api.mapreduce.AbstractMapReduce;
import com.continuuity.api.mapreduce.MapReduceSpecification;
import com.continuuity.api.metrics.Metrics;
import com.continuuity.api.procedure.AbstractProcedure;
import com.continuuity.api.procedure.ProcedureRequest;
import com.continuuity.api.procedure.ProcedureResponder;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.Longs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.util.Map;

import com.continuuity.api.annotation.Batch;
import com.continuuity.api.common.Bytes;
import com.continuuity.api.flow.flowlet.FlowletSpecification;
import java.util.Iterator;

import com.continuuity.api.data.dataset.SimpleTimeseriesTable;
import com.continuuity.api.data.dataset.TimeseriesTable;
import com.continuuity.api.data.dataset.table.Get;
import com.continuuity.api.data.dataset.table.Increment;
import com.continuuity.api.data.dataset.table.Row;
import com.continuuity.api.data.dataset.table.Table;
import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;

import com.continuuity.api.mapreduce.AbstractMapReduce;
import com.continuuity.api.mapreduce.MapReduceContext;
import com.continuuity.api.mapreduce.MapReduceSpecification;

import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.MD5Hash;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;

import com.continuuity.api.schedule.Schedule;
import com.continuuity.api.workflow.Workflow;
import com.continuuity.api.workflow.WorkflowSpecification;

import com.continuuity.api.procedure.ProcedureResponse;
import com.continuuity.api.procedure.ProcedureSpecification;
import com.google.common.collect.Maps;
import java.util.List;
import java.util.concurrent.TimeUnit;
import com.continuuity.api.ResourceSpecification;

import com.continuuity.flow.flowlet.ExternalProgramFlowlet;
import java.io.File;
import com.continuuity.api.flow.flowlet.FlowletContext;
import java.io.InputStream;
import com.google.common.base.Throwables;
import org.apache.commons.io.FileUtils;

/**
 * Application that analyzes sentiment of sentences as positive, negative or neutral.
 */
public class SentimentAnalysisApp implements Application {

  private static final Logger LOG = LoggerFactory.getLogger(SentimentAnalysisApp.class);

  /**
   * Configures the {@link com.continuuity.api.Application} by returning an
   * {@link com.continuuity.api.ApplicationSpecification}.
   *
   * @return An instance of {@code ApplicationSpecification}.
   */
  @Override
  public ApplicationSpecification configure() {
    return ApplicationSpecification.Builder.with()
    .setName("SentimentAnalysisApp")
    .setDescription("Application for Sentiment Analysis")
    .withStreams()
    .add(new Stream("sentence"))
    .withDataSets()
      .add(new Table("sentiments"))
      .add(new SimpleTimeseriesTable("text-sentiments"))
    .withFlows()
      .add(new SentimentAnalysisFlow())
//    .noProcedure()
    .withProcedures()
      .add(new SentimentAnalysisProcedure())
    .noMapReduce()
//    .withMapReduce()
//    .add(new SentimentAnalysisMapReduce())
    .noWorkflow()
//    .withWorkflows()
//        .add(new SentimentAnalysisWorkflow())
    .build();
  }
  
  
  public static class SentimentAnalysisFlow implements Flow {
    @Override
    public FlowSpecification configure() {
      return FlowSpecification.Builder.with()
      .setName("analysis")
      .setDescription("Analysis of text to generate sentiments")
      .withFlowlets()
      .add(new Normalization())
      .add(new Analyze())
      .add(new Update())
      .connect()
      .fromStream("sentence").to(new Normalization())
      .from(new Normalization()).to(new Analyze())
      .from(new Analyze()).to(new Update())
      .build();
    }
  }
  
  /**
   * Normalizes the sentences.
   */
  public static class Normalization extends AbstractFlowlet {
    private OutputEmitter<String> out;

    /**
     * Handlers to emit logs and metrics.
     */
    Metrics metrics;
    private static final Logger LOG = LoggerFactory.getLogger(Normalization.class);

  
    @ProcessInput
    public void process(StreamEvent event) {
      String text = Bytes.toString(Bytes.toBytes(event.getBody()));
      if (text != null) {
        metrics.count("data.processed.size", text.length());
        out.emit(text);
      } else {
        metrics.count("data.ignored.text", 1);
      }
    }
  }
  
  /**
   * Analyzes the sentences.
   */
  public static class Analyze extends ExternalProgramFlowlet<String, String> {
    private static final Logger LOG = LoggerFactory.getLogger(Analyze.class);
    
    @Output("sentiments")
    private OutputEmitter<String> sentiment;
    
    private File workDir;
    
    @Override
    protected ExternalProgram init(FlowletContext context) {
      try {
        InputStream in = this.getClass().getClassLoader()
        .getResourceAsStream("sentiment-process.zip");
        if (in != null) {
          workDir = new File("work");
          Unzipper.unzip(in, workDir);
          File bash = new File("/bin/bash");
          if (!bash.exists()) {
            bash = new File("/usr/bin/bash");
          }
          if (bash.exists()) {
            File program = new File(workDir, "sentiment/score-sentence");
            return new ExternalProgram(bash, program.getAbsolutePath());
          }
        }
        throw new RuntimeException("Unable to start process");
      } catch (IOException e) {
        throw Throwables.propagate(e);
      }
    }
    
    /**
     * This method will be called for each input event to transform the given input
     * into string before sending to external program for processing.
     *
     * @param input The input event.
     * @return A UTF-8 encoded string of the input, or null if to skip this input.
     */
    @Override
    protected String encode(String input) {
      return input;
    }
    
    /**
     * This method will be called when the external program returns the result. Child
     * class can do its own processing in this method or could return an object of type
     * for emitting to next Flowlet with the
     * {@link com.continuuity.api.flow.flowlet.OutputEmitter} returned by
     * {@link #getOutputEmitter()}.
     *
     * @param result The result from the external program.
     * @return The output to emit or {@code null} if nothing to emit.
     */
    @Override
    protected String processResult(String result) {
      LOG.info("result = {}", result);
      return result;
    }
    
    /**
     * Child class can override this method to return an OutputEmitter for writing data
     * to the next Flowlet.
     *
     */
    @Override
    protected OutputEmitter<String> getOutputEmitter() {
      return sentiment;
    }
    
    @Override
    protected void finish() {
      try {
        LOG.info("Deleting work dir {}", workDir);
        FileUtils.deleteDirectory(workDir);
      } catch (IOException e) {
        LOG.error("Could not delete work dir {}", workDir);
        throw Throwables.propagate(e);
      }
    }
    
  }
  
  public static class Update extends AbstractFlowlet {
    /**
     * Handlers to emit logs and metrics.
     */
    Metrics metrics;
    private static final Logger LOG = LoggerFactory.getLogger(Update.class);
    
    @UseDataSet("sentiments")
    private Table sentiments;
    
    @UseDataSet("text-sentiments")
    private SimpleTimeseriesTable textSentiments;
    
    @Override
    public FlowletSpecification configure() {
      return FlowletSpecification.Builder.with()
      .setName("update")
      .setDescription("Updates the sentiment counts")
      .build();
    }
    
    @Batch(1)
    @ProcessInput("sentiments")
    public void process(Iterator<String> sentimentItr) {
      while (sentimentItr.hasNext()) {
        String text = sentimentItr.next();
        Iterable<String> parts = Splitter.on("---").split(text);
        if (Iterables.size(parts) == 2) {
          String sentence = Iterables.get(parts, 0);
          String sentiment = Iterables.get(parts, 1);
          sentiments.increment(new Increment("aggregate", sentiment, 1));
          textSentiments.write(new TimeseriesTable.Entry(sentiment.getBytes(Charsets.UTF_8),
                                                         sentence.getBytes(Charsets.UTF_8),
                                                         System.currentTimeMillis()));
          metrics.count("sentiment." + sentiment, 1);
          LOG.info("Sentence = {}, Sentiment = {}", sentence, sentiment);
        } else {
          metrics.count("data.ignored.sentiments", 1);
        }
      }
    }
  }
  
  public static class SentimentAnalysisMapReduce extends AbstractMapReduce {
    
    // Annotation indicates the DataSets used in this MapReduce
    @UseDataSet("text-sentiments")
    private SimpleTimeseriesTable textSentiments;
    
    @UseDataSet("sentiments")
    private Table sentiments;
    
    @Override
    public MapReduceSpecification configure() {
      return MapReduceSpecification.Builder.with()
      .setName("SentimentCountMapReduce")
      .setDescription("Sentiment count MapReduce job")
      // Specify the DataSet for Mapper to read.
      .useInputDataSet("text-sentiments")
      // Specify the DataSet for Reducer to write.
      .useOutputDataSet("sentiments")
      .setMapperMemoryMB(512)
      .setReducerMemoryMB(1024)
      .build();
    }
    
    @Override
    public void beforeSubmit(MapReduceContext context) throws Exception {
      Job job = context.getHadoopJob();
      
      // A Mapper processes sentiments from the stored sentences
      context.setInput(textSentiments, textSentiments.getSplits());
      
      job.setMapperClass(SentimentMapper.class);
      
      // Set the output key of the Reducer class
      job.setMapOutputKeyClass(Text.class);
      
      // Set the output value of the Reducer class
      job.setMapOutputValueClass(IntWritable.class);
      
      job.setReducerClass(SentimentReducer.class);
    }
    
    /**
     * A Mapper that reads the sentiments from the text-sentiments
     * DataSet and creates key value pairs, where the key is the
     * sentiment and value is the occurence of a sentence. The Mapper
     * receives a key value pair (<byte[], TimeseriesTable.Entry>)
     * from the input DataSet and outputs data in another key value
     * pair (<Text, IntWritable>) to the Reducer.
     */
    public static class SentimentMapper extends Mapper<byte[], TimeseriesTable.Entry, Text,
    IntWritable> {
      // The output value
      private static final IntWritable ONE = new IntWritable(1);
      
      @Override
      public void map(byte[] key, TimeseriesTable.Entry entry, Context context)
      throws IOException, InterruptedException {
        // Send the key value pair to Reducer.
        String sentiment = Bytes.toString(key);
        context.write(new Text(sentiment), ONE);
      }
    }
    
    /**
     * Aggregate the number of sentences per sentiment and store the results in a Table.
     */
    public static class SentimentReducer extends Reducer<Text, IntWritable, byte[],
    IntWritable> {
      public void reduce(Text sentiment, Iterable<IntWritable> values, Context context)
      throws IOException, InterruptedException {
        int count = 0;
        // Get the count of sentences
        for (IntWritable val : values) {
          count += val.get();
        }
        // Store aggregated results in output DataSet.
        // Each sentiment's aggregated result is stored in a different row.
        // Each result, the number of sentences, is an entry of the row.
        // context.write(ROW_KEY, new TimeseriesTable.Entry(ROW_KEY, Bytes.toBytes(count), key.get()));
        context.write(Bytes.toBytes(sentiment.toString()), new IntWritable(count));
      }
    }
    
  } // Closes class SentimentAnalysisMapReduce
  
  /**
   * Implements a simple Workflow with one Workflow action to run 
   * the SentimentAnalysisMapReduce MapReduce job with a schedule
   * that runs every day at 11:00 A.M.
   */
  public class SentimentAnalysisWorkflow implements Workflow {
    
    @Override
    public WorkflowSpecification configure() {
      return WorkflowSpecification.Builder.with()
      .setName("SentimentAnalysisWorkflow")
      .setDescription("SentimentAnalysisWorkflow description")
      .onlyWith(new SentimentAnalysisMapReduce())
      .addSchedule(new Schedule("DailySchedule", "Run every day at 11:00 A.M.", "0 11 * * *",
                                Schedule.Action.START))
      .build();
    }
  }

  public static class SentimentAnalysisProcedure extends AbstractProcedure {

    private static final Logger LOG =
    LoggerFactory.getLogger(SentimentAnalysisProcedure.class);
    
    @UseDataSet("sentiments")
    private Table sentiments;
    
    @UseDataSet("text-sentiments")
    private SimpleTimeseriesTable textSentiments;
    
    @Handle("aggregates")
    public void sentimentAggregates(ProcedureRequest request, ProcedureResponder response)
    throws Exception {
      Row row = sentiments.get(new Get("aggregate"));
      Map<byte[], byte[]> result = row.getColumns();
      if (result == null) {
        response.error(ProcedureResponse.Code.FAILURE, "No sentiments processed.");
        return;
      }
      Map<String, Long> resp = Maps.newHashMap();
      for (Map.Entry<byte[], byte[]> entry : result.entrySet()) {
        resp.put(Bytes.toString(entry.getKey()), Bytes.toLong(entry.getValue()));
      }
      response.sendJson(ProcedureResponse.Code.SUCCESS, resp);
    }
    
    @Handle("sentiments")
    public void getSentiments(ProcedureRequest request, ProcedureResponder response)
    throws Exception {
      String sentiment = request.getArgument("sentiment");
      if (sentiment == null) {
        response.error(ProcedureResponse.Code.CLIENT_ERROR, "No sentiment sent");
        return;
      }
      
      long time = System.currentTimeMillis();
      List<SimpleTimeseriesTable.Entry> entries =
      textSentiments.read(sentiment.getBytes(Charsets.UTF_8),
                          time - TimeUnit.MILLISECONDS.convert(1, TimeUnit.DAYS),
                          time);
      LOG.info("sentiment:{}, entries:{}", sentiment, entries.size());
      Map<String, Long> textTimeMap = Maps.newHashMapWithExpectedSize(entries.size());
      for (SimpleTimeseriesTable.Entry entry : entries) {
        textTimeMap.put(Bytes.toString(entry.getValue()), entry.getTimestamp());
      }
      response.sendJson(ProcedureResponse.Code.SUCCESS, textTimeMap);
    }
    
    @Override
    public ProcedureSpecification configure() {
      return ProcedureSpecification.Builder.with()
      .setName("sentiment-query")
      .setDescription("Sentiments Procedure")
      .withResources(ResourceSpecification.BASIC)
      .build();
    }
  }
  
  
  
  
}

