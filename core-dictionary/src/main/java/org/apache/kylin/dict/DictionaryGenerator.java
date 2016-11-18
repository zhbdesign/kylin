/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.apache.kylin.dict;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.apache.kylin.common.util.Dictionary;
import org.apache.kylin.metadata.datatype.DataType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;

/**
 * @author yangli9
 */
@SuppressWarnings({ "rawtypes", "unchecked" })
public class DictionaryGenerator {

    private static final Logger logger = LoggerFactory.getLogger(DictionaryGenerator.class);

    private static final String[] DATE_PATTERNS = new String[] { "yyyy-MM-dd", "yyyyMMdd" };

    public static Dictionary<String> buildDictionary(DataType dataType, IDictionaryValueEnumerator valueEnumerator) throws IOException {
        Preconditions.checkNotNull(dataType, "dataType cannot be null");

        // build dict, case by data type
        IDictionaryBuilder builder;
        if (dataType.isDateTimeFamily()) {
            if (dataType.isDate())
                builder = new DateDictBuilder();
            else
                builder = new TimeDictBuilder();
        } else if (dataType.isNumberFamily()) {
            builder = new NumberDictBuilder();
        } else {
            builder = new StringDictBuilder();
        }

        return buildDictionary(builder, null, valueEnumerator);
    }

    public static Dictionary<String> buildDictionary(IDictionaryBuilder builder, DictionaryInfo dictInfo, IDictionaryValueEnumerator valueEnumerator) throws IOException {
        int baseId = 0; // always 0 for now
        int nSamples = 5;
        ArrayList<String> samples = new ArrayList<String>(nSamples);

        Dictionary<String> dict = builder.build(dictInfo, valueEnumerator, baseId, nSamples, samples);

        // log a few samples
        StringBuilder buf = new StringBuilder();
        for (String s : samples) {
            if (buf.length() > 0) {
                buf.append(", ");
            }
            buf.append(s.toString()).append("=>").append(dict.getIdFromValue(s));
        }
        logger.debug("Dictionary value samples: " + buf.toString());
        logger.debug("Dictionary cardinality: " + dict.getSize());
        logger.debug("Dictionary builder class: " + builder.getClass().getName());
        logger.debug("Dictionary class: " + dict.getClass().getName());
        return dict;
    }

    public static Dictionary mergeDictionaries(DataType dataType, List<DictionaryInfo> sourceDicts) throws IOException {
        return buildDictionary(dataType, new MultipleDictionaryValueEnumerator(sourceDicts));
    }

    private static class DateDictBuilder implements IDictionaryBuilder {
        @Override
        public Dictionary<String> build(DictionaryInfo dictInfo, IDictionaryValueEnumerator valueEnumerator, int baseId, int nSamples, ArrayList<String> returnSamples) throws IOException {
            final int BAD_THRESHOLD = 0;
            String matchPattern = null;
            String value;

            for (String ptn : DATE_PATTERNS) {
                matchPattern = ptn; // be optimistic
                int badCount = 0;
                SimpleDateFormat sdf = new SimpleDateFormat(ptn);
                while (valueEnumerator.moveNext()) {
                    value = valueEnumerator.current();
                    if (value == null || value.length() == 0)
                        continue;

                    try {
                        sdf.parse(value);
                        if (returnSamples.size() < nSamples && returnSamples.contains(value) == false)
                            returnSamples.add(value);
                    } catch (ParseException e) {
                        logger.info("Unrecognized date value: " + value);
                        badCount++;
                        if (badCount > BAD_THRESHOLD) {
                            matchPattern = null;
                            break;
                        }
                    }
                }
                if (matchPattern != null) {
                    return new DateStrDictionary(matchPattern, baseId);
                }
            }

            throw new IllegalStateException("Unrecognized datetime value");
        }
    }

    private static class TimeDictBuilder implements IDictionaryBuilder {
        @Override
        public Dictionary<String> build(DictionaryInfo dictInfo, IDictionaryValueEnumerator valueEnumerator, int baseId, int nSamples, ArrayList<String> returnSamples) throws IOException {
            return new TimeStrDictionary(); // base ID is always 0
        }
    }

    private static class StringDictBuilder implements IDictionaryBuilder {
        @Override
        public Dictionary<String> build(DictionaryInfo dictInfo, IDictionaryValueEnumerator valueEnumerator, int baseId, int nSamples, ArrayList<String> returnSamples) throws IOException {
            int maxTrieSizeInMB = TrieDictionaryForestBuilder.getMaxTrieSizeInMB();
            TrieDictionaryForestBuilder builder = new TrieDictionaryForestBuilder(new StringBytesConverter(), baseId, maxTrieSizeInMB);
            String value;
            while (valueEnumerator.moveNext()) {
                value = valueEnumerator.current();
                if (value == null)
                    continue;
                builder.addValue(value);
                if (returnSamples.size() < nSamples && returnSamples.contains(value) == false)
                    returnSamples.add(value);
            }
            return builder.build();
        }
    }

    private static class NumberDictBuilder implements IDictionaryBuilder {
        @Override
        public Dictionary<String> build(DictionaryInfo dictInfo, IDictionaryValueEnumerator valueEnumerator, int baseId, int nSamples, ArrayList<String> returnSamples) throws IOException {
            NumberDictionaryForestBuilder builder = new NumberDictionaryForestBuilder(baseId);
            String value;
            while (valueEnumerator.moveNext()) {
                value = valueEnumerator.current();
                if (StringUtils.isBlank(value)) // empty string is null for numbers
                    continue;

                builder.addValue(value);
                if (returnSamples.size() < nSamples && returnSamples.contains(value) == false)
                    returnSamples.add(value);
            }
            return builder.build();
        }
    }

}
