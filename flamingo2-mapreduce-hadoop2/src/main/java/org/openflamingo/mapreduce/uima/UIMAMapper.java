/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openflamingo.mapreduce.uima;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.filecache.DistributedCache;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.*;
import org.apache.uima.UIMAFramework;
import org.apache.uima.analysis_engine.AnalysisEngine;
import org.apache.uima.cas.CAS;
import org.apache.uima.cas.FSIterator;
import org.apache.uima.cas.Feature;
import org.apache.uima.cas.Type;
import org.apache.uima.cas.text.AnnotationFS;
import org.apache.uima.pear.tools.PackageBrowser;
import org.apache.uima.pear.tools.PackageInstaller;
import org.apache.uima.resource.ResourceSpecifier;
import org.apache.uima.util.FileUtils;
import org.apache.uima.util.XMLInputSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.*;

public class UIMAMapper extends MapReduceBase implements Mapper<Text, UIMADocument, Text, UIMADocument> {

    private static final Logger LOG = LoggerFactory.getLogger(UIMAMapper.class);

    private AnalysisEngine analysisEngine;

    private CAS cas;

    private Configuration config;

    private boolean storeshortnames = true;

    private List<Type> uimatypes = new ArrayList<Type>();

    private Map<String, Set<Feature>> featfilts = new HashMap<String, Set<Feature>>();

    private File installDir;

    @Override
    public void map(Text id, UIMADocument behemoth, OutputCollector<Text, UIMADocument> output, Reporter reporter) throws IOException {

        reporter.setStatus("UIMA : " + id.toString());

        // generate a CAS from the input document
        cas.reset();

        try {
            // does the input document have a some text?
            // if not - skip it
            if (behemoth.getText() == null) {
                LOG.debug(behemoth.getUrl().toString() + " has null text");
            } else {
                // detect language if specified by user
                String lang = this.config.get("uima.language", "en");
                cas.setDocumentLanguage(lang);
                cas.setDocumentText(behemoth.getText());

                // process it
                analysisEngine.process(cas);
                convertCASToUIMA(cas, behemoth, reporter);
            }
        } catch (Exception e) {
            reporter.incrCounter("UIMA", "Exception", 1);
            throw new IOException(e);
        }

        reporter.incrCounter("UIMA", "Document", 1);

        // dump the modified document
        output.collect(id, behemoth);
    }

    @Override
    public void configure(JobConf conf) {

        this.config = conf;

        storeshortnames = config.getBoolean("uima.store.short.names", true);

        File pearpath = new File(conf.get("uima.pear.path"));
        String pearname = pearpath.getName();

        URL urlPEAR = null;

        try {
            Path[] localArchives = DistributedCache.getLocalCacheFiles(conf);
            // identify the right archive
            for (Path localArchive : localArchives) {
                String localPath = localArchive.toUri().toString();
                LOG.info("Inspecting local paths " + localPath);
                if (!localPath.endsWith(pearname))
                    continue;
                urlPEAR = new URL("file://" + localPath);
                break;
            }
        } catch (IOException e) {
            throw new RuntimeException("Impossible to retrieve gate application from distributed cache", e);
        }

        if (urlPEAR == null)
            throw new RuntimeException("UIMA pear " + pearpath + " not available in distributed cache");

        File pearFile = new File(urlPEAR.getPath());

        // should check whether a different mapper has already unpacked it
        // but for now we just unpack in a different location for every mapper
        TaskAttemptID attempt = TaskAttemptID.forName(conf.get("mapred.task.id"));
        installDir = new File(pearFile.getParentFile(), attempt.toString());
        PackageBrowser instPear = PackageInstaller.installPackage(installDir, pearFile, true);

        // get the resources required for the AnalysisEngine
        org.apache.uima.resource.ResourceManager rsrcMgr = UIMAFramework.newDefaultResourceManager();

        // Create analysis engine from the installed PEAR package using
        // the created PEAR specifier
        XMLInputSource in;
        try {
            in = new XMLInputSource(instPear.getComponentPearDescPath());
            ResourceSpecifier specifier = UIMAFramework.getXMLParser().parseResourceSpecifier(in);
            analysisEngine = UIMAFramework.produceAnalysisEngine(specifier, rsrcMgr, null);
            cas = analysisEngine.newCAS();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        String[] featuresFilters = this.config.get("uima.features.filter", "").split(",");
        // the featurefilters have the following form : Type:featureName
        // we group them by annotation type
        for (String ff : featuresFilters) {
            String[] fp = ff.split(":");
            if (fp.length != 2)
                continue;
            Set<Feature> features = featfilts.get(fp[0]);
            if (features == null) {
                features = new HashSet<Feature>();
                featfilts.put(fp[0], features);
            }
            Feature f = cas.getTypeSystem().getFeatureByFullName(ff);
            if (f != null)
                features.add(f);
        }

        String[] annotTypes = this.config.get("uima.annotations.filter", "").split(",");
        uimatypes = new ArrayList<Type>(annotTypes.length);

        for (String type : annotTypes) {
            Type aType = cas.getTypeSystem().getType(type);
            uimatypes.add(aType);
        }
    }

    @Override
    public void close() throws IOException {
        if (cas != null)
            cas.release();
        if (analysisEngine != null)
            analysisEngine.destroy();
        if (installDir != null) {
            FileUtils.deleteRecursive(installDir);
        }
    }

    /**
     * convert the annotations from the CAS into the Behemoth format
     */
    private void convertCASToUIMA(CAS cas, UIMADocument uimaDocument, Reporter reporter) {
        String[] annotTypes = config.get("uima.annotations.filter", "").split(",");
        List<Type> uimatypes = new ArrayList<Type>(annotTypes.length);

        for (String type : annotTypes) {
            Type aType = this.cas.getTypeSystem().getType(type);
            uimatypes.add(aType);
        }

        FSIterator<AnnotationFS> annotIterator = this.cas.getAnnotationIndex().iterator();
        while (annotIterator.hasNext()) {
            AnnotationFS annotation = annotIterator.next();
            if (!uimatypes.contains(annotation.getType()))
                continue;
            String atype = annotation.getType().toString();
            // wanted type -> generate an annotation for it
            reporter.incrCounter("UIMA", atype, 1);

            Annotation target = new Annotation();
            // short version?
            if (storeshortnames)
                target.setType(annotation.getType().getShortName());
            else
                target.setType(atype);
            target.setStart(annotation.getBegin());
            target.setEnd(annotation.getEnd());
            // now get the features for this annotation
            Set<Feature> possiblefeatures = featfilts.get(atype);
            if (possiblefeatures != null) {
                // iterate on the expected features
                Iterator<Feature> possiblefeaturesIterator = possiblefeatures.iterator();
                while (possiblefeaturesIterator.hasNext()) {
                    Feature expectedFeature = possiblefeaturesIterator.next();
                    String fvalue = annotation.getFeatureValueAsString(expectedFeature);
                    if (fvalue == null)
                        fvalue = "null";
                    // always use the short names for the features
                    target.getFeatures().put(expectedFeature.getShortName(), fvalue);
                }
            }
            // add current annotation to Behemoth document
            uimaDocument.getAnnotations().add(target);
        }
    }
}
