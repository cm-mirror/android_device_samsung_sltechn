/*
 * Copyright (c) 2014, The CyanogenMod Project. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.telephony;

import static com.android.internal.telephony.RILConstants.*;

import android.content.Context;
import android.telephony.Rlog;
import android.os.AsyncResult;
import android.os.Message;
import android.os.Parcel;
import android.os.SystemProperties;
import android.telephony.PhoneNumberUtils;
import android.telephony.SignalStrength;
import com.android.internal.telephony.cdma.CdmaInformationRecords;
import com.android.internal.telephony.cdma.CdmaInformationRecords.CdmaSignalInfoRec;
import com.android.internal.telephony.cdma.SignalToneUtil;
import com.android.internal.telephony.uicc.IccCardApplicationStatus;
import com.android.internal.telephony.uicc.IccCardStatus;
import java.util.ArrayList;
import java.util.Collections;

/**
 * RIL customization for Galaxy Alpha (S801) LTE devices
 *
 * {@hide}
 */
public class SlteRIL extends RIL {

    private static final int RIL_REQUEST_DIAL_EMERGENCY = 10016;
    private static final int RIL_REQUEST_DIAL_EMERGENCY_LL = 10001;
    private static final int RIL_UNSOL_DEVICE_READY_NOTI = 11008;
    private static final int RIL_UNSOL_AM = 11010;
    private static final int RIL_UNSOL_WB_AMR_STATE = 11017;
    private static final int RIL_UNSOL_RESPONSE_HANDOVER = 11021;
    private static final int RIL_UNSOL_SRVCC_HANDOVER = 11029;
    private static final int RIL_REQUEST_ACTIVATE_DATA_CALL = 11037;
    private static final int RIL_UNSOL_ON_SS_LL = 11055;

    private boolean mIsGsm = true;
    private boolean isLollipopRadio = true;

    public SlteRIL(Context context, int networkMode, int cdmaSubscription) {
        super(context, networkMode, cdmaSubscription, null);
        mQANElements = 6;
    }

    public SlteRIL(Context context, int preferredNetworkType,
            int cdmaSubscription, Integer instanceId) {
        super(context, preferredNetworkType, cdmaSubscription, instanceId);
        mQANElements = 6;
    }

    @Override
    public void
    dial(String address, int clirMode, UUSInfo uusInfo, Message result) {
        if (PhoneNumberUtils.isEmergencyNumber(address)) {
            dialEmergencyCall(address, clirMode, result);
            return;
        }

        RILRequest rr = RILRequest.obtain(RIL_REQUEST_DIAL, result);

        rr.mParcel.writeString(address);
        rr.mParcel.writeInt(clirMode);
        rr.mParcel.writeInt(0);     // CallDetails.call_type
        rr.mParcel.writeInt(1);     // CallDetails.call_domain
        rr.mParcel.writeString(""); // CallDetails.getCsvFromExtras

        if (uusInfo == null) {
            rr.mParcel.writeInt(0); // UUS information is absent
        } else {
            rr.mParcel.writeInt(1); // UUS information is present
            rr.mParcel.writeInt(uusInfo.getType());
            rr.mParcel.writeInt(uusInfo.getDcs());
            rr.mParcel.writeByteArray(uusInfo.getUserData());
        }

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    @Override
    protected Object
    responseIccCardStatus(Parcel p) {
        IccCardApplicationStatus appStatus = null;

        IccCardStatus cardStatus = new IccCardStatus();
        cardStatus.setCardState(p.readInt());
        cardStatus.setUniversalPinState(p.readInt());
        cardStatus.mGsmUmtsSubscriptionAppIndex = p.readInt();
        cardStatus.mCdmaSubscriptionAppIndex = p.readInt();
        cardStatus.mImsSubscriptionAppIndex = p.readInt();

        int numApplications = p.readInt();

        // limit to maximum allowed applications
        if (numApplications > IccCardStatus.CARD_MAX_APPS) {
            numApplications = IccCardStatus.CARD_MAX_APPS;
        }
        cardStatus.mApplications = new IccCardApplicationStatus[numApplications];

        for (int i = 0 ; i < numApplications ; i++) {
            appStatus = new IccCardApplicationStatus();
            appStatus.app_type       = appStatus.AppTypeFromRILInt(p.readInt());
            appStatus.app_state      = appStatus.AppStateFromRILInt(p.readInt());
            appStatus.perso_substate = appStatus.PersoSubstateFromRILInt(p.readInt());
            appStatus.aid            = p.readString();
            appStatus.app_label      = p.readString();
            appStatus.pin1_replaced  = p.readInt();
            appStatus.pin1           = appStatus.PinStateFromRILInt(p.readInt());
            appStatus.pin2           = appStatus.PinStateFromRILInt(p.readInt());
            p.readInt(); // pin1_num_retries
            p.readInt(); // puk1_num_retries
            p.readInt(); // pin2_num_retries
            p.readInt(); // puk2_num_retries
            p.readInt(); // perso_unblock_retries

            cardStatus.mApplications[i] = appStatus;
        }

        // For Sprint LTE only SIM
        if (appStatus != null
                && numApplications == 1
                && !mIsGsm
                && appStatus.app_type == appStatus.AppTypeFromRILInt(2)) {
            cardStatus.mApplications = new IccCardApplicationStatus[3];
            cardStatus.mApplications[0] = appStatus;
            cardStatus.mGsmUmtsSubscriptionAppIndex = 0;
            cardStatus.mCdmaSubscriptionAppIndex = 1;
            cardStatus.mImsSubscriptionAppIndex = 2;

            IccCardApplicationStatus appStatus2 = new IccCardApplicationStatus();
            appStatus2.app_type       = appStatus2.AppTypeFromRILInt(4); // csim state
            appStatus2.app_state      = appStatus.app_state;
            appStatus2.perso_substate = appStatus.perso_substate;
            appStatus2.aid            = appStatus.aid;
            appStatus2.app_label      = appStatus.app_label;
            appStatus2.pin1_replaced  = appStatus.pin1_replaced;
            appStatus2.pin1           = appStatus.pin1;
            appStatus2.pin2           = appStatus.pin2;
            cardStatus.mApplications[cardStatus.mCdmaSubscriptionAppIndex] = appStatus2;

            IccCardApplicationStatus appStatus3 = new IccCardApplicationStatus();
            appStatus3.app_type       = appStatus3.AppTypeFromRILInt(5); // ims state
            appStatus3.app_state      = appStatus.app_state;
            appStatus3.perso_substate = appStatus.perso_substate;
            appStatus3.aid            = appStatus.aid;
            appStatus3.app_label      = appStatus.app_label;
            appStatus3.pin1_replaced  = appStatus.pin1_replaced;
            appStatus3.pin1           = appStatus.pin1;
            appStatus3.pin2           = appStatus.pin2;
            cardStatus.mApplications[cardStatus.mImsSubscriptionAppIndex] = appStatus3;
        }
        return cardStatus;
    }

    @Override
    protected Object
    responseCallList(Parcel p) {
        int num;
        int voiceSettings;
        ArrayList<DriverCall> response;
        DriverCall dc;

        num = p.readInt();
        response = new ArrayList<DriverCall>(num);

        if (RILJ_LOGV) {
            riljLog("responseCallList: num=" + num +
                    " mEmergencyCallbackModeRegistrant=" + mEmergencyCallbackModeRegistrant +
                    " mTestingEmergencyCall=" + mTestingEmergencyCall.get());
        }
        for (int i = 0 ; i < num ; i++) {
            dc = new DriverCall();

            dc.state = DriverCall.stateFromCLCC(p.readInt());
            dc.index = p.readInt() & 0xff;
            dc.TOA = p.readInt();
            dc.isMpty = (0 != p.readInt());
            dc.isMT = (0 != p.readInt());
            dc.als = p.readInt();
            voiceSettings = p.readInt();
            dc.isVoice = (0 != voiceSettings);
            if (mIsGsm) {
                boolean isVideo;
                if (!isLollipopRadio)
                    isVideo = (0 != p.readInt());       // Samsung CallDetails
                int call_type = p.readInt();            // Samsung CallDetails
                int call_domain = p.readInt();          // Samsung CallDetails
                String csv = p.readString();            // Samsung CallDetails
            }
            dc.isVoicePrivacy = (0 != p.readInt());
            dc.number = p.readString();
            int np = p.readInt();
            dc.numberPresentation = DriverCall.presentationFromCLIP(np);
            dc.name = p.readString();
            if (!isLollipopRadio) {
                dc.namePresentation = p.readInt();
            } else {
                dc.namePresentation = DriverCall.presentationFromCLIP(p.readInt());
            }
            int uusInfoPresent = p.readInt();
            if (uusInfoPresent == 1) {
                dc.uusInfo = new UUSInfo();
                dc.uusInfo.setType(p.readInt());
                dc.uusInfo.setDcs(p.readInt());
                byte[] userData = p.createByteArray();
                dc.uusInfo.setUserData(userData);
                riljLogv(String.format("Incoming UUS : type=%d, dcs=%d, length=%d",
                                dc.uusInfo.getType(), dc.uusInfo.getDcs(),
                                dc.uusInfo.getUserData().length));
                riljLogv("Incoming UUS : data (string)="
                        + new String(dc.uusInfo.getUserData()));
                riljLogv("Incoming UUS : data (hex): "
                        + IccUtils.bytesToHexString(dc.uusInfo.getUserData()));
            } else {
                riljLogv("Incoming UUS : NOT present!");
            }

            // Make sure there's a leading + on addresses with a TOA of 145
            dc.number = PhoneNumberUtils.stringFromStringAndTOA(dc.number, dc.TOA);

            response.add(dc);

            if (dc.isVoicePrivacy) {
                mVoicePrivacyOnRegistrants.notifyRegistrants();
                riljLog("InCall VoicePrivacy is enabled");
            } else {
                mVoicePrivacyOffRegistrants.notifyRegistrants();
                riljLog("InCall VoicePrivacy is disabled");
            }
        }

        Collections.sort(response);

        if ((num == 0) && mTestingEmergencyCall.getAndSet(false)) {
            if (mEmergencyCallbackModeRegistrant != null) {
                riljLog("responseCallList: call ended, testing emergency call," +
                            " notify ECM Registrants");
                mEmergencyCallbackModeRegistrant.notifyRegistrant();
            }
        }

        return response;
    }

    @Override
    protected Object
    responseSignalStrength(Parcel p) {
        int gsmSignalStrength = p.readInt() & 0xff;
        int gsmBitErrorRate = p.readInt();
        int cdmaDbm = p.readInt();
        int cdmaEcio = p.readInt();
        int evdoDbm = p.readInt();
        int evdoEcio = p.readInt();
        int evdoSnr = p.readInt();
        int lteSignalStrength = p.readInt();
        int lteRsrp = p.readInt();
        int lteRsrq = p.readInt();
        int lteRssnr = p.readInt();
        int lteCqi = p.readInt();
        int tdScdmaRscp = p.readInt();
        // constructor sets default true, makeSignalStrengthFromRilParcel does not set it

        if ((lteSignalStrength & 0xff) == 255 || lteSignalStrength == 99) {
            lteSignalStrength = 99;
            lteRsrp = SignalStrength.INVALID;
            lteRsrq = SignalStrength.INVALID;
            lteRssnr = SignalStrength.INVALID;
            lteCqi = SignalStrength.INVALID;
        } else {
            lteSignalStrength &= 0xff;
        }

        if (RILJ_LOGD)
            riljLog("gsmSignalStrength:" + gsmSignalStrength + " gsmBitErrorRate:" + gsmBitErrorRate +
                    " cdmaDbm:" + cdmaDbm + " cdmaEcio:" + cdmaEcio + " evdoDbm:" + evdoDbm +
                    " evdoEcio: " + evdoEcio + " evdoSnr:" + evdoSnr +
                    " lteSignalStrength:" + lteSignalStrength + " lteRsrp:" + lteRsrp +
                    " lteRsrq:" + lteRsrq + " lteRssnr:" + lteRssnr + " lteCqi:" + lteCqi +
                    " tdScdmaRscp:" + tdScdmaRscp + " isGsm:" + (mIsGsm ? "true" : "false"));

        return new SignalStrength(gsmSignalStrength, gsmBitErrorRate, cdmaDbm, cdmaEcio, evdoDbm,
                evdoEcio, evdoSnr, lteSignalStrength, lteRsrp, lteRsrq, lteRssnr, lteCqi,
                tdScdmaRscp, mIsGsm);
    }

    @Override
    protected void notifyRegistrantsCdmaInfoRec(CdmaInformationRecords infoRec) {
        final int response = RIL_UNSOL_CDMA_INFO_REC;

        if (infoRec.record instanceof CdmaSignalInfoRec) {
            CdmaSignalInfoRec rec = (CdmaSignalInfoRec) infoRec.record;
            if (rec != null
                    && rec.isPresent
                    && rec.signalType == SignalToneUtil.IS95_CONST_IR_SIGNAL_IS54B
                    && rec.alertPitch == SignalToneUtil.IS95_CONST_IR_ALERT_MED
                    && rec.signal == SignalToneUtil.IS95_CONST_IR_SIG_IS54B_L) {
                /* Drop record, otherwise IS95_CONST_IR_SIG_IS54B_L tone will
                 * continue to play after the call is connected */
                return;
            }
        }
        super.notifyRegistrantsCdmaInfoRec(infoRec);
    }

    @Override
    public void setPhoneType(int phoneType) {
        super.setPhoneType(phoneType);
        //mIsGsm = (phoneType != RILConstants.CDMA_PHONE);
    }

    @Override
    protected void
    processUnsolicited (Parcel p) {
        Object ret;
        int dataPosition = p.dataPosition(); // save off position within the Parcel
        int response = p.readInt();

        if (isLollipopRadio) {
            int newResponse = response;
            switch(response) {
                case RIL_UNSOL_ON_SS_LL:
                    newResponse = RIL_UNSOL_ON_SS;
                    break;
            }
            if (newResponse != response) {
                p.setDataPosition(dataPosition);
                p.writeInt(newResponse);
            }
        }

        switch(response) {
            case RIL_UNSOL_RESPONSE_IMS_NETWORK_STATE_CHANGED:
                ret = responseVoid(p);
                break;
            case RIL_UNSOL_DEVICE_READY_NOTI:
                ret = responseVoid(p);
                break;
            case RIL_UNSOL_AM:
                ret = responseString(p);
                break;
            case RIL_UNSOL_WB_AMR_STATE:
                ret = responseInts(p);
                break;
            case RIL_UNSOL_RESPONSE_HANDOVER:
                ret = responseVoid(p);
                break;
            case RIL_UNSOL_SRVCC_HANDOVER:
                ret = responseVoid(p);
                break;
            default:
                // Rewind the Parcel
                p.setDataPosition(dataPosition);
                // Forward responses that we are not overriding to the super class
                super.processUnsolicited(p);
                return;
        }
    }

    @Override
    public void
    acceptCall (Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_ANSWER, result);

        if (isLollipopRadio) {
            rr.mParcel.writeInt(1);
            rr.mParcel.writeInt(0);
        }

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }


    private void
    dialEmergencyCall(String address, int clirMode, Message result) {
        RILRequest rr;

        if (isLollipopRadio) {
            rr = RILRequest.obtain(RIL_REQUEST_DIAL_EMERGENCY_LL, result);
        } else {
            rr = RILRequest.obtain(RIL_REQUEST_DIAL_EMERGENCY, result);
        }
        rr.mParcel.writeString(address);
        rr.mParcel.writeInt(clirMode);
        rr.mParcel.writeInt(0);        // CallDetails.call_type
        rr.mParcel.writeInt(3);        // CallDetails.call_domain
        rr.mParcel.writeString("");    // CallDetails.getCsvFromExtra
        rr.mParcel.writeInt(0);        // Unknown

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    @Override
    protected RILRequest
    processSolicited (Parcel p) {
        int serial, error;
        boolean found = false;
        int dataPosition = p.dataPosition(); // save off position within the Parcel
        serial = p.readInt();
        error = p.readInt();
        RILRequest rr = null;
        /* Pre-process the reply before popping it */
        synchronized (mRequestList) {
            RILRequest tr = mRequestList.get(serial);
            if (tr != null && tr.mSerial == serial) {
                if (error == 0 || p.dataAvail() > 0) {
                    try {switch (tr.mRequest) {
                            /* Get those we're interested in */
                        case RIL_REQUEST_DATA_REGISTRATION_STATE:
                            rr = tr;
                            break;
                    }} catch (Throwable thr) {
                        // Exceptions here usually mean invalid RIL responses
                        if (tr.mResult != null) {
                            AsyncResult.forMessage(tr.mResult, null, thr);
                            tr.mResult.sendToTarget();
                        }
                        return tr;
                    }
                }
            }
        }
        if (rr == null) {
            /* Nothing we care about, go up */
            p.setDataPosition(dataPosition);
            // Forward responses that we are not overriding to the super class
            return super.processSolicited(p);
        }
        rr = findAndRemoveRequestFromList(serial);
        if (rr == null) {
            return rr;
        }
        Object ret = null;
        if (error == 0 || p.dataAvail() > 0) {
            switch (rr.mRequest) {
                case RIL_REQUEST_DATA_REGISTRATION_STATE: ret = responseDataRegistrationState(p); break;
                default:
                    throw new RuntimeException("Unrecognized solicited response: " + rr.mRequest);
            }
            //break;
        }
        if (RILJ_LOGD) riljLog(rr.serialString() + "< " + requestToString(rr.mRequest)
                               + " " + retToString(rr.mRequest, ret));
        if (rr.mResult != null) {
            AsyncResult.forMessage(rr.mResult, ret, null);
            rr.mResult.sendToTarget();
        }
        return rr;
    }

    private Object
    responseDataRegistrationState(Parcel p) {
        String response[] = (String[])responseStrings(p);
        /* DANGER WILL ROBINSON
         * In some cases from Vodaphone we are receiving a RAT of 102
         * while in tunnels of the metro. Lets Assume that if we
         * receive 102 we actually want a RAT of 2 for EDGE service */
        if (response.length > 4 &&
            response[0].equals("1") &&
            response[3].equals("102")) {
            response[3] = "2";
        }
        return response;
    }
}
