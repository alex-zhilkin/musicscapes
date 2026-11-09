package local.musicscapes.tv;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Same tokenizer, pooling, and normalized vectors are used on desktop and TV. */
public final class SemanticEncoder implements AutoCloseable {
    private final Map<String,Integer> vocabulary=new HashMap<>();
    private final OrtEnvironment environment;
    private final OrtSession session;
    public SemanticEncoder(String modelPath,InputStream vocab)throws Exception {
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(vocab,StandardCharsets.UTF_8))){
            String line;int index=0;while((line=reader.readLine())!=null)vocabulary.put(line,index++);
        }
        environment=OrtEnvironment.getEnvironment();
        environment.setTelemetry(false);
        try(OrtSession.SessionOptions options=new OrtSession.SessionOptions()){
            options.setIntraOpNumThreads(2);options.setInterOpNumThreads(1);
            // Infrequent, background embeddings should not busy-wait between jobs.
            options.addConfigEntry("session.intra_op.allow_spinning","0");
            options.addConfigEntry("session.inter_op.allow_spinning","0");
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            options.setMemoryPatternOptimization(false);options.setCPUArenaAllocator(false);
            session=environment.createSession(modelPath,options);
        }
    }
    public long[] tokenize(String text,int maximum){
        String normalized=Normalizer.normalize(text.toLowerCase(Locale.ROOT),Normalizer.Form.NFD).replaceAll("\\p{M}","");
        StringBuilder basic=new StringBuilder();
        for(int offset=0;offset<normalized.length();){
            int c=normalized.codePointAt(offset);offset+=Character.charCount(c);
            int type=Character.getType(c);
            if(Character.isWhitespace(c)||Character.isSpaceChar(c)){basic.append(' ');continue;}
            if(type==Character.CONTROL||type==Character.FORMAT)continue;
            boolean punctuation=(c>=33&&c<=47)||(c>=58&&c<=64)||(c>=91&&c<=96)||(c>=123&&c<=126)||type==Character.CONNECTOR_PUNCTUATION||type==Character.DASH_PUNCTUATION||type==Character.START_PUNCTUATION||type==Character.END_PUNCTUATION||type==Character.OTHER_PUNCTUATION||type==Character.INITIAL_QUOTE_PUNCTUATION||type==Character.FINAL_QUOTE_PUNCTUATION;
            boolean chinese=c>=0x4e00&&c<=0x9fff;
            if(punctuation||chinese)basic.append(' ').appendCodePoint(c).append(' ');else basic.appendCodePoint(c);
        }
        List<Long> ids=new ArrayList<>();ids.add(101L);
        outer:for(String token:basic.toString().trim().split("\\s+")){
            if(token.isEmpty())continue;
            List<Integer> pieces=new ArrayList<>();int start=0;boolean unknown=token.length()>100;
            while(start<token.length()&&!unknown){
                int end=token.length();Integer found=null;
                while(end>start){found=vocabulary.get((start>0?"##":"")+token.substring(start,end));if(found!=null)break;end--;}
                if(found==null){unknown=true;break;}pieces.add(found);start=end;
            }
            if(unknown){pieces.clear();pieces.add(100);}
            for(int piece:pieces){if(ids.size()>=maximum-1)break outer;ids.add((long)piece);}
        }
        ids.add(102L);long[] output=new long[ids.size()];for(int i=0;i<output.length;i++)output[i]=ids.get(i);return output;
    }
    public synchronized float[] encode(String text)throws Exception {
        long[] tokens=tokenize(text,128),ones=new long[tokens.length];java.util.Arrays.fill(ones,1);
        try(OnnxTensor ids=OnnxTensor.createTensor(environment,new long[][]{tokens});OnnxTensor mask=OnnxTensor.createTensor(environment,new long[][]{ones});OnnxTensor types=OnnxTensor.createTensor(environment,new long[][]{new long[tokens.length]})){
            Map<String,OnnxTensor> inputs=new LinkedHashMap<>();inputs.put("input_ids",ids);inputs.put("attention_mask",mask);
            if(session.getInputNames().contains("token_type_ids"))inputs.put("token_type_ids",types);
            try(OrtSession.Result result=session.run(inputs)){
                float[][][] embeddings=(float[][][])result.get(0).getValue();float[] vector=new float[embeddings[0][0].length];
                for(float[] token:embeddings[0])for(int i=0;i<vector.length;i++)vector[i]+=token[i]/tokens.length;
                return normalize(vector);
            }
        }
    }
    public static float[] normalize(float[] vector){double norm=0;for(float x:vector)norm+=x*x;norm=Math.sqrt(norm);if(norm>0)for(int i=0;i<vector.length;i++)vector[i]/=norm;return vector;}
    @Override public void close()throws Exception {session.close();}
}
