package WordCountApp;

import com.continuuity.api.flow.flowlet.AbstractFlowlet;
import com.continuuity.api.flow.flowlet.OutputEmitter;
import com.continuuity.api.flow.flowlet.StreamEvent;

public class WordSplitterFlowlet extends AbstractFlowlet {

  private OutputEmitter<String> wordOutput;
  
  private OutputEmitter<String[]> wordArrayOutput;

  public void process(StreamEvent event) {
    // Input is a String, need to split it by whitespace
    byte [] rawInput = event.getBody().array();
    String inputString = new String(rawInput);
    String [] words = inputString.split("\\s+");
    
    // We have an array of words, now remove all non-alpha characters
    for (int i=0; i<words.length; i++) {
      words[i] = words[i].replaceAll("[^A-Za-z]", "");
    }

    // Send the array of words to the associater
    wordArrayOutput.emit(words);
    
    // Then emit each word to the counter
    for (String word : words) {
      wordOutput.emit(word);
    }
  }
}