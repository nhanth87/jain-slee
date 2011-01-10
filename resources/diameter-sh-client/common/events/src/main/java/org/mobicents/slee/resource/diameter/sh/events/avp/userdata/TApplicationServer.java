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

import net.java.slee.resource.diameter.sh.events.avp.userdata.ApplicationServer;
import net.java.slee.resource.diameter.sh.events.avp.userdata.Extension;


/**
 * <p>Java class for tApplicationServer complex type.
 * 
 * <p>The following schema fragment specifies the expected content contained within this class.
 * 
 * <pre>
 * &lt;complexType name="tApplicationServer">
 *   &lt;complexContent>
 *     &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
 *       &lt;sequence>
 *         &lt;element name="ServerName" type="{}tSIP_URL"/>
 *         &lt;element name="DefaultHandling" type="{}tDefaultHandling" minOccurs="0"/>
 *         &lt;element name="ServiceInfo" type="{}tServiceInfo" minOccurs="0"/>
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
@XmlType(name = "tApplicationServer", propOrder = {
    "serverName",
    "defaultHandling",
    "serviceInfo",
    "extension",
    "any"
})
public class TApplicationServer implements ApplicationServer {

    @XmlElement(name = "ServerName", required = true)
    protected String serverName;
    @XmlElement(name = "DefaultHandling")
    protected Short defaultHandling;
    @XmlElement(name = "ServiceInfo")
    protected String serviceInfo;
    @XmlElement(name = "Extension")
    protected TExtension extension;
    @XmlAnyElement(lax = true)
    protected List<Object> any;

    /* (non-Javadoc)
     * @see org.mobicents.slee.resource.diameter.sh.events.avp.userdata.ApplicationServer#getServerName()
     */
    public String getServerName() {
        return serverName;
    }

    /* (non-Javadoc)
     * @see org.mobicents.slee.resource.diameter.sh.events.avp.userdata.ApplicationServer#setServerName(java.lang.String)
     */
    public void setServerName(String value) {
        this.serverName = value;
    }

    /* (non-Javadoc)
     * @see org.mobicents.slee.resource.diameter.sh.events.avp.userdata.ApplicationServer#getDefaultHandling()
     */
    public Short getDefaultHandling() {
        return defaultHandling;
    }

    /* (non-Javadoc)
     * @see org.mobicents.slee.resource.diameter.sh.events.avp.userdata.ApplicationServer#setDefaultHandling(java.lang.Short)
     */
    public void setDefaultHandling(Short value) {
        this.defaultHandling = value;
    }

    /* (non-Javadoc)
     * @see org.mobicents.slee.resource.diameter.sh.events.avp.userdata.ApplicationServer#getServiceInfo()
     */
    public String getServiceInfo() {
        return serviceInfo;
    }

    /* (non-Javadoc)
     * @see org.mobicents.slee.resource.diameter.sh.events.avp.userdata.ApplicationServer#setServiceInfo(java.lang.String)
     */
    public void setServiceInfo(String value) {
        this.serviceInfo = value;
    }

    /* (non-Javadoc)
     * @see org.mobicents.slee.resource.diameter.sh.events.avp.userdata.ApplicationServer#getExtension()
     */
    public Extension getExtension() {
        return extension;
    }

    /* (non-Javadoc)
     * @see org.mobicents.slee.resource.diameter.sh.events.avp.userdata.ApplicationServer#setExtension(org.mobicents.slee.resource.diameter.sh.events.avp.userdata.TExtension)
     */
    public void setExtension(Extension value) {
        this.extension = (TExtension) value;
    }

    /* (non-Javadoc)
     * @see org.mobicents.slee.resource.diameter.sh.events.avp.userdata.ApplicationServer#getAny()
     */
    public List<Object> getAny() {
        if (any == null) {
            any = new ArrayList<Object>();
        }
        return this.any;
    }

}
