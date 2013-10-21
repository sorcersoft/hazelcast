/*
 * Copyright (c) 2008-2013, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Properties;

public abstract class AbstractXmlConfigHelper {

    private final static Logger logger = LoggerFactory.getLogger(AbstractXmlConfigHelper.class);

    protected boolean domLevel3 = true;

    public static class IterableNodeList implements Iterable<Node> {

        private final NodeList parent;
        private final int maximum;
        private final short nodeType;

        public IterableNodeList(final Node node) {
            this(node.getChildNodes());
        }

        public IterableNodeList(final NodeList list) {
            this(list, (short) 0);
        }

        public IterableNodeList(final Node node, short nodeType) {
            this(node.getChildNodes(), nodeType);
        }

        public IterableNodeList(final NodeList parent, short nodeType) {
            this.parent = parent;
            this.nodeType = nodeType;
            this.maximum = parent.getLength();
        }

        public Iterator<Node> iterator() {
            return new Iterator<Node>() {

                private int index = 0;
                private Node next;

                private boolean findNext() {
                    next = null;
                    for (; index < maximum; index++) {
                        final Node item = parent.item(index);
                        if (nodeType == 0 || item.getNodeType() == nodeType) {
                            next = item;
                            return true;
                        }
                    }
                    return false;
                }

                public boolean hasNext() {
                    return findNext();
                }

                public Node next() {
                    if (findNext()) {
                        index++;
                        return next;
                    }
                    throw new NoSuchElementException();
                }

                public void remove() {
                    throw new UnsupportedOperationException();
                }
            };
        }
    }

    protected String xmlToJavaName(final String name) {
        final StringBuilder builder = new StringBuilder();
        final char[] charArray = name.toCharArray();
        boolean dash = false;
        final StringBuilder token = new StringBuilder();
        for (char aCharArray : charArray) {
            if (aCharArray == '-') {
                appendToken(builder, token);
                dash = true;
                continue;
            }
            token.append(dash ? Character.toUpperCase(aCharArray) : aCharArray);
            dash = false;
        }
        appendToken(builder, token);
        return builder.toString();
    }

    protected void appendToken(final StringBuilder builder, final StringBuilder token) {
        String string = token.toString();
        if ("Jvm".equals(string)) {
            string = "JVM";
        }
        builder.append(string);
        token.setLength(0);
    }

    protected String getTextContent(final Node node) {
        if (node != null) {
            final String text;
            if (domLevel3) {
                text = node.getTextContent();
            } else {
                text = getTextContentOld(node);
            }
            return text != null ? text.trim() : "";
        }
        return "";
    }

    private String getTextContentOld(final Node node) {
        final Node child = node.getFirstChild();
        if (child != null) {
            final Node next = child.getNextSibling();
            if (next == null) {
                return hasTextContent(child) ? child.getNodeValue() : "";
            }
            final StringBuilder buf = new StringBuilder();
            appendTextContents(node, buf);
            return buf.toString();
        }
        return "";
    }

    private void appendTextContents(final Node node, final StringBuilder buf) {
        Node child = node.getFirstChild();
        while (child != null) {
            if (hasTextContent(child)) {
                buf.append(child.getNodeValue());
            }
            child = child.getNextSibling();
        }
    }

    protected final boolean hasTextContent(final Node node) {
        final short nodeType = node.getNodeType();
        return nodeType != Node.COMMENT_NODE && nodeType != Node.PROCESSING_INSTRUCTION_NODE;
    }

    public final String cleanNodeName(final Node node) {
        return cleanNodeName(node.getNodeName());
    }

    public static String cleanNodeName(final String nodeName) {
        String name = nodeName;
        if (name != null) {
            name = nodeName.replaceAll("\\w+:", "").toLowerCase();
        }
        return name;
    }

    protected boolean checkTrue(final String value) {
        return "true".equalsIgnoreCase(value) ||
                "yes".equalsIgnoreCase(value) ||
                "on".equalsIgnoreCase(value);
    }

    protected int getIntegerValue(final String parameterName, final String value, final int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (final Exception e) {
            logger.info( parameterName + " parameter value, [" + value
                    + "], is not a proper integer. Default value, [" + defaultValue + "], will be used!",e );
            return defaultValue;
        }
    }

    protected String getAttribute(org.w3c.dom.Node node, String attName) {
        final Node attNode = node.getAttributes().getNamedItem(attName);
        if (attNode == null)
            return null;
        return getTextContent(attNode);
    }


    protected void fillProperties(final org.w3c.dom.Node node, Properties properties) {
        if (properties == null) return;
        for (org.w3c.dom.Node n : new IterableNodeList(node.getChildNodes())) {
            if (n.getNodeType() == org.w3c.dom.Node.TEXT_NODE || n.getNodeType() == org.w3c.dom.Node.COMMENT_NODE) {
                continue;
            }
            final String name = cleanNodeName(n.getNodeName());
            final String propertyName;
            if ("property".equals(name)) {
                propertyName = getTextContent(n.getAttributes().getNamedItem("name")).trim();
            } else {
                // old way - probably should be deprecated
                propertyName = name;
            }
            final String value = getTextContent(n).trim();
            properties.setProperty(propertyName, value);
        }
    }





}
