/**
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */
package org.openmrs.module.patientnarratives.web.controller;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.*;
import org.openmrs.api.context.Context;
import org.openmrs.obs.ComplexData;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;

import javax.servlet.http.HttpServletRequest;

import com.xuggle.mediatool.IMediaWriter;
import com.xuggle.mediatool.ToolFactory;
import com.xuggle.xuggler.IAudioSamples;
import com.xuggle.xuggler.ICodec;
import com.xuggle.xuggler.IContainer;
import com.xuggle.xuggler.IPacket;
import com.xuggle.xuggler.IStream;
import com.xuggle.xuggler.IStreamCoder;
import com.xuggle.xuggler.IVideoPicture;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Date;
import java.util.List;

/**
 * This is the Controller of the Video recording stuff, this controller will receive the user submitted
 * video record as two multipart http requests. 1. Video blob, 2. Audio blob.
 * (Since WebRTC currently doesn't support generating Video with Audio we had to do this)
 *
 * After that the two blobs are been merged together using the "Xuggler library" which is a (FFMPEG Java wrapper)
 * Then the merged single file will be saved as a OpenMRS complex observation inside Hard disc (~/.OpenMRS/complex_obs folder)
 */
@Controller
public class WebRtcMediaStreamController {

    private String returnUrl;
    public final static String FORM_PATH = "/module/patientnarratives/webRtcMedia.form";
    protected final Log log = LogFactory.getLog(getClass());

    private File tempMergedVideoFile = null;

    @RequestMapping(FORM_PATH)
    public ModelAndView handleRequest(HttpServletRequest request) throws Exception {

        if (request instanceof MultipartHttpServletRequest) {
            MultipartHttpServletRequest multipartRequest = (MultipartHttpServletRequest) request;
            MultipartFile videofile             = (MultipartFile) multipartRequest.getFile("video");
            MultipartFile audiofile             = (MultipartFile) multipartRequest.getFile("audio");

            /**
             * Xuggler merge process of the two binary streams
             */
            try{
                tempMergedVideoFile = File.createTempFile("mergedVideoFile", ".flv");
                String mergedUrl = tempMergedVideoFile.getCanonicalPath();

                IMediaWriter mWriter = ToolFactory.makeWriter(mergedUrl); //output file

                IContainer containerVideo = IContainer.make();
                IContainer containerAudio = IContainer.make();

                InputStream videoInputStream = videofile.getInputStream();
                InputStream audioInputStream = audiofile.getInputStream();

                if (containerVideo.open(videoInputStream, null) < 0)
                    throw new IllegalArgumentException("Cant find " + videoInputStream);

                if (containerAudio.open(audioInputStream, null) < 0)
                    throw new IllegalArgumentException("Cant find " + audioInputStream);

                int numStreamVideo = containerVideo.getNumStreams();
                int numStreamAudio = containerAudio.getNumStreams();

                System.out.println("Number of video streams: "+numStreamVideo + "\n" + "Number of audio streams: "+numStreamAudio );

                int videostreamt = -1; //this is the video stream id
                int audiostreamt = -1;

                IStreamCoder  videocoder = null;

                for(int i=0; i<numStreamVideo; i++){
                    IStream stream = containerVideo.getStream(i);
                    IStreamCoder code = stream.getStreamCoder();

                    if(code.getCodecType() == ICodec.Type.CODEC_TYPE_VIDEO)
                    {
                        videostreamt = i;
                        videocoder = code;
                        break;
                    }
                }

                for(int i=0; i<numStreamAudio; i++){
                    IStream stream = containerAudio.getStream(i);
                    IStreamCoder code = stream.getStreamCoder();

                    if(code.getCodecType() == ICodec.Type.CODEC_TYPE_AUDIO)
                    {
                        audiostreamt = i;
                        break;
                    }
                }

                if (videostreamt == -1) throw new RuntimeException("No video steam found");
                if (audiostreamt == -1) throw new RuntimeException("No audio steam found");

                if(videocoder.open()<0 ) throw new RuntimeException("Cant open video coder");
                IPacket packetvideo = IPacket.make();

                IStreamCoder audioCoder = containerAudio.getStream(audiostreamt).getStreamCoder();

                if(audioCoder.open()<0 ) throw new RuntimeException("Cant open audio coder");
                mWriter.addAudioStream(1, 1, audioCoder.getChannels(), audioCoder.getSampleRate());

                mWriter.addVideoStream(0, 0, videocoder.getWidth(), videocoder.getHeight());

                IPacket packetaudio = IPacket.make();

                while(containerVideo.readNextPacket(packetvideo) >= 0 ||
                        containerAudio.readNextPacket(packetaudio) >= 0){

                    if(packetvideo.getStreamIndex() == videostreamt){

                        //video packet
                        IVideoPicture picture = IVideoPicture.make(videocoder.getPixelType(),
                                videocoder.getWidth(),
                                videocoder.getHeight());
                        int offset = 0;
                        while (offset < packetvideo.getSize()){
                            int bytesDecoded = videocoder.decodeVideo(picture,
                                    packetvideo,
                                    offset);
                            if(bytesDecoded < 0) throw new RuntimeException("bytesDecoded not working");
                            offset += bytesDecoded;

                            if(picture.isComplete()){
                                System.out.println(picture.getPixelType());
                                mWriter.encodeVideo(0, picture);

                            }
                        }
                    }

                    if(packetaudio.getStreamIndex() == audiostreamt){
                        //audio packet

                        IAudioSamples samples = IAudioSamples.make(512,
                                audioCoder.getChannels(),
                                IAudioSamples.Format.FMT_S32);
                        int offset = 0;
                        while(offset<packetaudio.getSize())
                        {
                            int bytesDecodedaudio = audioCoder.decodeAudio(samples,
                                    packetaudio,
                                    offset);
                            if (bytesDecodedaudio < 0)
                                throw new RuntimeException("could not detect audio");
                            offset += bytesDecodedaudio;

                            if (samples.isComplete()){
                                mWriter.encodeAudio(1, samples);
                            }
                        }
                    }
                }
            }catch (Exception e){
                log.error(e);
                e.getStackTrace();
            }
        }

        saveAndTransferVideoComplexObs();

        returnUrl = request.getContextPath() + "/module/patientnarratives/patientNarrativesForm.form";
        return new ModelAndView(new RedirectView(returnUrl));
    }

    /**
     * Saving the file as a Complex observation
     */
    public void saveAndTransferVideoComplexObs(){

        try{
            List<Encounter> encounters = Context.getEncounterService().getEncounters(null, null, null, null, null, null, true);
            Encounter lastEncounter = encounters.get(encounters.size()-1);

            Person patient = lastEncounter.getPatient();
            ConceptComplex conceptComplex = Context.getConceptService().getConceptComplex(14);
            Location location = Context.getLocationService().getDefaultLocation();
            Obs obs = new Obs(patient, conceptComplex, new Date(), location) ;

            String mergedUrl = tempMergedVideoFile.getCanonicalPath();
            InputStream out1 = new FileInputStream(new File(mergedUrl));

            ComplexData complexData = new ComplexData("mergedFile1.flv", out1);
            obs.setComplexData(complexData);
            obs.setEncounter(lastEncounter);

            Context.getObsService().saveObs(obs, null);
            tempMergedVideoFile.delete();

        }catch (Exception e){
            log.error(e);
        }
    }
}
