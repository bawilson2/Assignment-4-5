#include <RFduinoBLE.h>
#define NUM_VALS_MEDIAN 5


float vals[NUM_VALS_MEDIAN];
float valsSorted[NUM_VALS_MEDIAN];
boolean firstVal;

void swap(float a[], int l, int r) {
  float  tmp = a[l];
  a[l] = a[r];
  a[r] = tmp;
}


void quicksort(float a[], int l, int r) {
  if (r > l) {
    int i = l - 1, j = r, tmp;
    while (i < j) {
      while (a[++i] < a[r]);
      while (a[--j] > a[r] && j > i);
      swap(a, i, j);
    }
    swap(a, i, r);

    quicksort(a, l, i - 1);
    quicksort(a, i + 1, r);
  }
}




void addValue(float val) {
  if (firstVal == true) {
    for (int i = 0; i < NUM_VALS_MEDIAN; i++) {
      valsSorted[i] = val;
    }
    for (int i = 0; i < NUM_VALS_MEDIAN; i++) {
      vals[i] = val;
    }
  }
  else {
    for (int i = 1; i < NUM_VALS_MEDIAN; i++) {
      vals[i - 1] = vals[i];
    }
    vals[NUM_VALS_MEDIAN - 1] = val;
    for (int i = 0; i < NUM_VALS_MEDIAN; i++) {
      valsSorted[i] = vals[i];
    }
  }
  firstVal = false;
}

float getMedian() {
  quicksort(valsSorted, 0, NUM_VALS_MEDIAN);
  return valsSorted[ (int)floor(NUM_VALS_MEDIAN / 2) ];
}


void setup() {
  RFduinoBLE.advertisementData = "HRM";
  Serial.begin(9600);

  RFduinoBLE.begin();
}


void loop() {
  RFduino_ULPDelay( MILLISECONDS(10) );

  float light = analogRead(6);

  Serial.println(light);

  float mappedValue = map(light, 0, 1024, 0, 127);

  addValue(mappedValue);

  RFduinoBLE.sendFloat(getMedian());
}














