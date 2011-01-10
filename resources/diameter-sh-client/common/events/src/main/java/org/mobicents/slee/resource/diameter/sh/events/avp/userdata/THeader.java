/*
 * JBoss, Home of Professional Open Source
 *
 * Copyright 2010, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @authors tag. All rights reserved.
 * See the copyright.txt in the distribution for a full listing
 * of individual contributors.
 *
 * This copyrighted material is made available to anyone wishing to use,
 * modify, copy, or redistribute it subject to the terms and conditions
 * of the GNU General Public License, v. 2.0.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License,
 * v. 2.0 along with this distribution; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA 02110-1301, USA.
 */
package org.mobicents.slee.resource.diameter.sh.events.avp.userdata;

import java.util.ArrayList;
import java.util.List;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAnyElement;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlType;

import net.java.slee.resource.diameter.sh.events.avp.userdata.Extension;
import net.java.slee.resource.diameter.sh.events.avp.userdata.Header;


/**
 * <p>Java class for tHeader complex type.
 * 
 * <p>The following schema fragment specifies the expected content contained within this class.
 * 
 * <pre>
 * &lt;complexType name="tHeader">
 *   &lt;complexContent>
 *     &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
 *       &lt;sequence>
 *         &lt;element name="Header" type="{}tString"/>
 *         &lt;element name="Content" type="{}tString" minOccurs="0"/>
 *         &lt;element name="Extension" type="{}tExtension" minOccurs="0"/>
 *         &lt;any processContents='lax' namespace='##other' maxOccurs="unbounded" minOccurs="0"/>
 *       &lt;/sequence>
 *     &lt;/restriction>
 *   &lt;/complexContent>
 * &lt;/complexType>
 * </pre>
 * 
 * @author <a href="mailto:brainslog@gmail.com"> Alexandre Mendonca </a>
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "tHeader", propOrder = {
    "header",
    "content",
    "extension",
    "any"
})
public class THeader implements Header {

    @XmlElement(name = "Header", required = true)
    protected String header;
    @XmlElement(name = "Content")
    protected String content;
    @XmlElement(name = "Extension")
    protected TExtension extension;
    @XmlAnyElement(lax = true)
    protected List<Object> any;

    /* (non-Javadoc)
     * @see org.mobicents.slee.resource.diameter.sh.events.avp.userdata.Header#getHeader()
     */
    public String getHeader() {
        return header;
    }

    /* (non-Javadoc)
     * @see org.mobicents.slee.resource.diameter.sh.events.avp.userdata.Header#setHeader(java.lang.String)
     */
    public void setHeader(String value) {
        this.header = value;
    }

    /* (non-Javadoc)
     * @see org.mobicents.slee.resource.diameter.sh.events.avp.userdata.Header#getContent()
     */
    public String getContent() {
        return content;
    }

    /* (non-Javadoc)
     * @see org.mobicents.slee.resource.diameter.sh.events.avp.userdata.Header#setContent(java.lang.String)
     */
    public void setContent(String value) {
        this.content = value;
    }

    /* (non-Javadoc)
     * @see org.mobicents.slee.resource.diameter.sh.events.avp.userdata.Header#getExtension()
     */
    public Extension getExtension() {
        return extension;
    }

    /* (non-Javadoc)
     * @see org.mobicents.slee.resource.diameter.sh.events.avp.userdata.Header#setExtension(org.mobicents.slee.resource.diameter.sh.events.avp.userdata.Extension)
     */
    public void setExtension(Extension value) {
        this.extension = (TExtension) value;
    }

    /* (non-Javadoc)
     * @see org.mobicents.slee.resource.diameter.sh.events.avp.userdata.Header#getAny()
     */
    public List<Object> getAny() {
        if (any == null) {
            any = new ArrayList<Object>();
        }
        return this.any;
    }

}
