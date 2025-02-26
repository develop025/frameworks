/*
 * Copyright (C) 2011 The Android Open Source Project
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
package com.mediatek.keyguard.util;

import android.test.InstrumentationTestRunner;
import junit.framework.TestSuite;
import com.mediatek.common.featureoption.FeatureOption;
import com.mediatek.keyguard.test.KeyguardSelectorViewTest;
import com.mediatek.keyguard.test.KeyguardAntiTheftViewTest;


public class MTKKeyguardTestRunner extends JUnitInstrumentationTestRunner {
    @Override
    public TestSuite getAllTests(){
        TestSuite tests = new TestSuite();
		tests.addTestSuite(KeyguardSelectorViewTest.class);

        if(FeatureOption.MTK_PRIVACY_PROTECTION_LOCK) {
            tests.addTestSuite(KeyguardAntiTheftViewTest.class);
        }    
        return tests;
    }
}

