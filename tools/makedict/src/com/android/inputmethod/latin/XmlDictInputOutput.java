/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.inputmethod.latin;

import com.android.inputmethod.latin.FusionDictionary.WeightedString;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.TreeSet;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Reads and writes XML files for a FusionDictionary.
 *
 * All functions in this class are static.
 */
public class XmlDictInputOutput {

    private static final String WORD_TAG = "w";
    private static final String BIGRAM_TAG = "bigram";
    private static final String SHORTCUT_TAG = "shortcut";
    private static final String FREQUENCY_ATTR = "f";
    private static final String WORD_ATTR = "word";

    /**
     * SAX handler for a unigram XML file.
     */
    static private class UnigramHandler extends DefaultHandler {
        // Parser states
        private static final int NONE = 0;
        private static final int START = 1;
        private static final int WORD = 2;
        private static final int BIGRAM = 4;
        private static final int END = 5;
        private static final int UNKNOWN = 6;

        final FusionDictionary mDictionary;
        int mState; // the state of the parser
        int mFreq; // the currently read freq
        String mWord; // the current word
        final HashMap<String, ArrayList<WeightedString>> mShortcutsMap;
        final HashMap<String, ArrayList<WeightedString>> mBigramsMap;

        /**
         * Create the handler.
         *
         * @param dict the dictionary to construct.
         * @param bigrams the bigrams as a map. This may be empty, but may not be null.
         */
        public UnigramHandler(final FusionDictionary dict,
                final HashMap<String, ArrayList<WeightedString>> shortcuts,
                final HashMap<String, ArrayList<WeightedString>> bigrams) {
            mDictionary = dict;
            mShortcutsMap = shortcuts;
            mBigramsMap = bigrams;
            mWord = "";
            mState = START;
            mFreq = 0;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attrs) {
            if (WORD_TAG.equals(localName)) {
                mState = WORD;
                mWord = "";
                for (int attrIndex = 0; attrIndex < attrs.getLength(); ++attrIndex) {
                    final String attrName = attrs.getLocalName(attrIndex);
                    if (FREQUENCY_ATTR.equals(attrName)) {
                        mFreq = Integer.parseInt(attrs.getValue(attrIndex));
                    }
                }
            } else {
                mState = UNKNOWN;
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (WORD == mState) {
                // The XML parser is free to return text in arbitrary chunks one after the
                // other. In particular, this happens in some implementations when it finds
                // an escape code like "&amp;".
                mWord += String.copyValueOf(ch, start, length);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            if (WORD == mState) {
                mDictionary.add(mWord, mFreq, mShortcutsMap.get(mWord), mBigramsMap.get(mWord));
                mState = START;
            }
        }
    }

    static private class AssociativeListHandler extends DefaultHandler {
        private final String SRC_TAG;
        private final String SRC_ATTRIBUTE;
        private final String DST_TAG;
        private final String DST_ATTRIBUTE;
        private final String DST_FREQ;

        // In this version of the XML file, the bigram frequency is given as an int 0..XML_MAX
        private final static int XML_MAX = 256;
        // In memory and in the binary dictionary the bigram frequency is 0..MEMORY_MAX
        private final static int MEMORY_MAX = 16;
        private final static int XML_TO_MEMORY_RATIO = XML_MAX / MEMORY_MAX;

        private String mSrc;
        private final HashMap<String, ArrayList<WeightedString>> mAssocMap;

        public AssociativeListHandler(final String srcTag, final String srcAttribute,
                final String dstTag, final String dstAttribute, final String dstFreq) {
            SRC_TAG = srcTag;
            SRC_ATTRIBUTE = srcAttribute;
            DST_TAG = dstTag;
            DST_ATTRIBUTE = dstAttribute;
            DST_FREQ = dstFreq;
            mSrc = null;
            mAssocMap = new HashMap<String, ArrayList<WeightedString>>();
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attrs) {
            if (SRC_TAG.equals(localName)) {
                mSrc = attrs.getValue(uri, SRC_ATTRIBUTE);
            } else if (DST_TAG.equals(localName)) {
                String dst = attrs.getValue(uri, DST_ATTRIBUTE);
                int freq = Integer.parseInt(attrs.getValue(uri, DST_FREQ));
                WeightedString bigram = new WeightedString(dst, freq / XML_TO_MEMORY_RATIO);
                ArrayList<WeightedString> bigramList = mAssocMap.get(mSrc);
                if (null == bigramList) bigramList = new ArrayList<WeightedString>();
                bigramList.add(bigram);
                mAssocMap.put(mSrc, bigramList);
            }
        }

        public HashMap<String, ArrayList<WeightedString>> getAssocMap() {
            return mAssocMap;
        }
    }

    /**
     * SAX handler for a bigram XML file.
     */
    static private class BigramHandler extends AssociativeListHandler {
        private final static String BIGRAM_W1_TAG = "bi";
        private final static String BIGRAM_W2_TAG = "w";
        private final static String BIGRAM_W1_ATTRIBUTE = "w1";
        private final static String BIGRAM_W2_ATTRIBUTE = "w2";
        private final static String BIGRAM_FREQ_ATTRIBUTE = "p";

        public BigramHandler() {
            super(BIGRAM_W1_TAG, BIGRAM_W1_ATTRIBUTE, BIGRAM_W2_TAG, BIGRAM_W2_ATTRIBUTE,
                    BIGRAM_FREQ_ATTRIBUTE);
        }

        public HashMap<String, ArrayList<WeightedString>> getBigramMap() {
            return getAssocMap();
        }
    }

    /**
     * SAX handler for a shortcut XML file.
     */
    static private class ShortcutHandler extends AssociativeListHandler {
        private final static String ENTRY_TAG = "entry";
        private final static String ENTRY_ATTRIBUTE = "shortcut";
        private final static String TARGET_TAG = "target";
        private final static String REPLACEMENT_ATTRIBUTE = "replacement";
        private final static String TARGET_PRIORITY_ATTRIBUTE = "priority";

        public ShortcutHandler() {
            super(ENTRY_TAG, ENTRY_ATTRIBUTE, TARGET_TAG, REPLACEMENT_ATTRIBUTE,
                    TARGET_PRIORITY_ATTRIBUTE);
        }

        public HashMap<String, ArrayList<WeightedString>> getShortcutMap() {
            return getAssocMap();
        }
    }

    /**
     * Reads a dictionary from an XML file.
     *
     * This is the public method that will parse an XML file and return the corresponding memory
     * representation.
     *
     * @param unigrams the file to read the data from.
     * @param shortcuts the file to read the shortcuts from, or null.
     * @param bigrams the file to read the bigrams from, or null.
     * @return the in-memory representation of the dictionary.
     */
    public static FusionDictionary readDictionaryXml(final InputStream unigrams,
            final InputStream shortcuts, final InputStream bigrams)
            throws SAXException, IOException, ParserConfigurationException {
        final SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        final SAXParser parser = factory.newSAXParser();
        final BigramHandler bigramHandler = new BigramHandler();
        if (null != bigrams) parser.parse(bigrams, bigramHandler);

        final ShortcutHandler shortcutHandler = new ShortcutHandler();
        if (null != shortcuts) parser.parse(shortcuts, shortcutHandler);

        final FusionDictionary dict = new FusionDictionary();
        final UnigramHandler unigramHandler =
                new UnigramHandler(dict, shortcutHandler.getShortcutMap(),
                        bigramHandler.getBigramMap());
        parser.parse(unigrams, unigramHandler);
        return dict;
    }

    /**
     * Reads a dictionary in the first, legacy XML format
     *
     * This method reads data from the parser and creates a new FusionDictionary with it.
     * The format parsed by this method is the format used before Ice Cream Sandwich,
     * which has no support for bigrams or shortcuts.
     * It is important to note that this method expects the parser to have already eaten
     * the first, all-encompassing tag.
     *
     * @param xpp the parser to read the data from.
     * @return the parsed dictionary.
     */

    /**
     * Writes a dictionary to an XML file.
     *
     * The output format is the "second" format, which supports bigrams and shortcuts.
     *
     * @param destination a destination stream to write to.
     * @param dict the dictionary to write.
     */
    public static void writeDictionaryXml(Writer destination, FusionDictionary dict)
            throws IOException {
        final TreeSet<Word> set = new TreeSet<Word>();
        for (Word word : dict) {
            set.add(word);
        }
        // TODO: use an XMLSerializer if this gets big
        destination.write("<wordlist format=\"2\">\n");
        for (Word word : set) {
            destination.write("  <" + WORD_TAG + " " + WORD_ATTR + "=\"" + word.mWord + "\" "
                    + FREQUENCY_ATTR + "=\"" + word.mFrequency + "\">");
            if (null != word.mShortcutTargets) {
                destination.write("\n");
                for (WeightedString target : word.mShortcutTargets) {
                    destination.write("    <" + SHORTCUT_TAG + " " + FREQUENCY_ATTR + "=\""
                            + target.mFrequency + "\">" + target.mWord + "</" + SHORTCUT_TAG
                            + ">\n");
                }
                destination.write("  ");
            }
            if (null != word.mBigrams) {
                destination.write("\n");
                for (WeightedString bigram : word.mBigrams) {
                    destination.write("    <" + BIGRAM_TAG + " " + FREQUENCY_ATTR + "=\""
                            + bigram.mFrequency + "\">" + bigram.mWord + "</" + BIGRAM_TAG + ">\n");
                }
                destination.write("  ");
            }
            destination.write("</" + WORD_TAG + ">\n");
        }
        destination.write("</wordlist>\n");
        destination.close();
    }
}