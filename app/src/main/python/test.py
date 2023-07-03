import numpy as np
from scipy.signal import find_peaks, butter, lfilter


def main(magnitude_data):
    # Constants
    sampling_rate = 12  # Sampling rate in Hz
    # parameters approximation based on several datasets
    height = 11.25
    threshold = 0.012
    prominence = 3

    gyroscope_magnitude = np.array(magnitude_data)

    # Low-pass filtering
    cutoff_freq = 2  # The cutoff frequency (Nyquist frequency = sampling_rate / 2)
    normalized_cutoff_freq = cutoff_freq / (sampling_rate / 2)
    b, a = butter(4, normalized_cutoff_freq, btype='low', analog=False)
    gyroscope_magnitude = lfilter(b, a, gyroscope_magnitude)

    # Detect peaks
    peaks, _ = find_peaks(gyroscope_magnitude, distance=sampling_rate * 0.25,
                          height=height,
                          threshold=threshold,
                          prominence=prominence)

    # Indicate peak/non-peak elements in the data variable
    data_indicator = np.zeros_like(gyroscope_magnitude)
    data_indicator[peaks] = 1

    # Get list of peak indices
    peak_indices = np.where(data_indicator == 1)[0]

    num_steps = len(peak_indices)

    return num_steps
